package com.acme.platform.workflow;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.OutboxEventPublisher;
import com.acme.platform.platform.RequestContextHolder;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 令牌推进引擎：固定节点枚举 + 显式状态机，不引入 BPMN 或脚本表达式。
 *
 * <p>所有推进都在调用方已开启的租户事务里执行；同一实例的并发动作由
 * {@code SELECT ... FOR UPDATE} 串行化。令牌跳数上限 200，超限写
 * {@code CONDITION_NO_MATCH} 异常并暂停，防止定义中的死循环拖垮 Worker。
 */
@Component
public class WorkflowEngine {

    static final int MAX_TOKEN_HOPS = 200;

    private final WorkflowStore store;
    private final WorkflowConditionEvaluator evaluator;
    private final AssigneeResolver assigneeResolver;
    private final OutboxEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public WorkflowEngine(WorkflowStore store, WorkflowConditionEvaluator evaluator,
                          AssigneeResolver assigneeResolver, OutboxEventPublisher eventPublisher,
                          ObjectMapper objectMapper) {
        this.store = store;
        this.evaluator = evaluator;
        this.assigneeResolver = assigneeResolver;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /** 从给定令牌开始推进；调用方必须已经在租户事务内。 */
    public void advance(UUID instanceId, UUID tokenId) {
        WorkflowStore.InstanceRow instance = store.lockInstance(instanceId);
        if (!"running".equals(instance.status())) {
            return;
        }
        WorkflowSchema schema = schemaOf(instance);
        Map<String, Object> variables = variables(instance);
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(tokenId);
        int hops = 0;

        while (!queue.isEmpty()) {
            UUID currentId = queue.poll();
            hops++;
            if (hops > MAX_TOKEN_HOPS) {
                WorkflowStore.TokenRow token = store.token(currentId).orElse(null);
                store.insertException(instanceId, null, "CONDITION_NO_MATCH",
                        "令牌推进超过 " + MAX_TOKEN_HOPS + " 跳，流程已暂停",
                        "{\"tokenId\":\"" + currentId + "\"}");
                if (token != null) {
                    store.updateTokenStatus(token.id(), "waiting");
                }
                return;
            }
            WorkflowStore.TokenRow token = store.token(currentId).orElse(null);
            if (token == null || !"active".equals(token.status())) {
                continue;
            }
            WorkflowSchema.Node node = schema.node(token.nodeKey());
            if (node == null) {
                store.insertException(instanceId, null, "CONDITION_NO_MATCH",
                        "令牌指向不存在的节点：" + token.nodeKey(), "{}");
                store.updateTokenStatus(token.id(), "waiting");
                return;
            }
            switch (node.type()) {
                case "start" -> moveToSingle(instance, schema, token, queue);
                case "exclusive" -> exclusive(instance, schema, token, variables, queue);
                case "parallel_split" -> split(instance, schema, token, queue);
                case "parallel_join" -> join(instance, schema, token, queue);
                case "end" -> {
                    store.updateTokenStatus(token.id(), "completed");
                    store.log(instanceId, null, node.key(), "active", "completed",
                            "end", null, null);
                    maybeComplete(instance);
                }
                case "approval" -> awaitApproval(instance, token, node);
                case "timer" -> {
                    store.insertTimer(instanceId, token.id(), node.key(),
                            OffsetDateTime.now().plusMinutes(node.delayMinutes()));
                    store.updateTokenStatus(token.id(), "waiting");
                    store.log(instanceId, null, node.key(), "active", "waiting",
                            "timer", null, null);
                }
                case "service" -> {
                    String idempotencyKey = instanceId + ":" + node.key();
                    store.insertServiceTask(instanceId, token.id(), node.key(), node.handler(),
                            idempotencyKey, node.maxAttempts(), "{}", null);
                    store.updateTokenStatus(token.id(), "waiting");
                    store.log(instanceId, null, node.key(), "active", "waiting",
                            "service", null, null);
                }
                default -> {
                    store.insertException(instanceId, null, "CONDITION_NO_MATCH",
                            "未知节点类型：" + node.type(), "{}");
                    store.updateTokenStatus(token.id(), "waiting");
                    return;
                }
            }
        }
    }

    /** 审批结果推进：按 approved / returned 选择出边。 */
    public void outcome(UUID instanceId, UUID tokenId, String outcome) {
        WorkflowStore.InstanceRow instance = store.lockInstance(instanceId);
        if (!"running".equals(instance.status())) {
            return;
        }
        WorkflowSchema schema = schemaOf(instance);
        WorkflowStore.TokenRow token = store.token(tokenId).orElse(null);
        if (token == null) {
            return;
        }
        WorkflowSchema.Edge edge = outcomeEdge(schema, token.nodeKey(), outcome);
        if (edge == null) {
            store.updateTokenStatus(tokenId, "completed");
            maybeComplete(instance);
            return;
        }
        store.moveToken(tokenId, edge.to());
        advance(instanceId, tokenId);
    }

    /** 定时器或服务任务完成后，从当前节点的出边继续。 */
    public void resume(UUID instanceId, UUID tokenId) {
        WorkflowStore.TokenRow token = store.token(tokenId).orElse(null);
        if (token == null) {
            return;
        }
        WorkflowStore.InstanceRow instance = store.lockInstance(instanceId);
        if (!"running".equals(instance.status())) {
            return;
        }
        WorkflowSchema schema = schemaOf(instance);
        List<WorkflowSchema.Edge> outgoing = schema.outgoing(token.nodeKey());
        if (outgoing.isEmpty()) {
            store.updateTokenStatus(tokenId, "completed");
            maybeComplete(instance);
            return;
        }
        store.moveToken(tokenId, outgoing.getFirst().to());
        advance(instanceId, tokenId);
    }

    public WorkflowSchema schemaOf(UUID instanceId) {
        WorkflowStore.InstanceRow instance = store.instance(instanceId)
                .orElseThrow(() -> ApiException.notFound("WORKFLOW_INSTANCE_NOT_FOUND",
                        "流程实例不存在：" + instanceId));
        return schemaOf(instance);
    }

    private WorkflowSchema schemaOf(WorkflowStore.InstanceRow instance) {
        WorkflowStore.DefinitionRow definition = store.definition(instance.definitionId())
                .orElseThrow(() -> ApiException.notFound("WORKFLOW_DEFINITION_NOT_FOUND",
                        "工作流定义不存在：" + instance.definitionId()));
        return WorkflowSchema.parse(objectMapper, definition.schema());
    }

    private Map<String, Object> variables(WorkflowStore.InstanceRow instance) {
        try {
            return objectMapper.readValue(instance.variables(),
                    new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException error) {
            return Map.of();
        }
    }

    private void moveToSingle(WorkflowStore.InstanceRow instance, WorkflowSchema schema,
                              WorkflowStore.TokenRow token, Deque<UUID> queue) {
        List<WorkflowSchema.Edge> outgoing = schema.outgoing(token.nodeKey());
        if (outgoing.isEmpty()) {
            store.updateTokenStatus(token.id(), "completed");
            maybeComplete(instance);
            return;
        }
        store.moveToken(token.id(), outgoing.getFirst().to());
        queue.add(token.id());
    }

    private void exclusive(WorkflowStore.InstanceRow instance, WorkflowSchema schema,
                           WorkflowStore.TokenRow token, Map<String, Object> variables,
                           Deque<UUID> queue) {
        WorkflowSchema.Node node = schema.node(token.nodeKey());
        WorkflowSchema.Edge selected = null;
        for (WorkflowSchema.Edge edge : schema.outgoing(node.key())) {
            if (!edge.when().isBlank()) {
                continue;
            }
            if (edge.condition() == null || evaluator.matches(edge.condition(), variables)) {
                selected = edge;
                break;
            }
        }
        if (selected == null && node.defaultEdge() != null && !node.defaultEdge().isBlank()) {
            selected = schema.outgoing(node.key()).stream()
                    .filter(edge -> node.defaultEdge().equals(edge.key()))
                    .findFirst().orElse(null);
        }
        if (selected == null) {
            store.insertException(instance.id(), null, "CONDITION_NO_MATCH",
                    "排他网关没有命中任何出边：" + node.key(), "{}");
            store.updateTokenStatus(token.id(), "waiting");
            return;
        }
        store.moveToken(token.id(), selected.to());
        queue.add(token.id());
    }

    private void split(WorkflowStore.InstanceRow instance, WorkflowSchema schema,
                       WorkflowStore.TokenRow token, Deque<UUID> queue) {
        List<WorkflowSchema.Edge> outgoing = schema.outgoing(token.nodeKey());
        if (outgoing.isEmpty()) {
            store.updateTokenStatus(token.id(), "completed");
            maybeComplete(instance);
            return;
        }
        for (WorkflowSchema.Edge edge : outgoing) {
            UUID branch = store.insertToken(instance.id(), edge.to(), "active");
            queue.add(branch);
        }
        store.updateTokenStatus(token.id(), "completed");
        store.log(instance.id(), null, token.nodeKey(), "active", "completed",
                "split", null, null);
    }

    private void join(WorkflowStore.InstanceRow instance, WorkflowSchema schema,
                      WorkflowStore.TokenRow token, Deque<UUID> queue) {
        store.updateTokenStatus(token.id(), "waiting");
        List<WorkflowStore.TokenRow> waiting = store.tokensAtNode(instance.id(),
                token.nodeKey(), "waiting");
        if (waiting.size() < schema.incomingCount(token.nodeKey())) {
            return;
        }
        for (WorkflowStore.TokenRow row : waiting) {
            store.updateTokenStatus(row.id(), "completed");
        }
        List<WorkflowSchema.Edge> outgoing = schema.outgoing(token.nodeKey());
        if (outgoing.isEmpty()) {
            maybeComplete(instance);
            return;
        }
        UUID merged = store.insertToken(instance.id(), outgoing.getFirst().to(), "active");
        queue.add(merged);
        store.log(instance.id(), null, token.nodeKey(), "waiting", "completed",
                "join", null, null);
    }

    private void awaitApproval(WorkflowStore.InstanceRow instance,
                               WorkflowStore.TokenRow token, WorkflowSchema.Node node) {
        LocalDate today = LocalDate.now();
        List<UUID> assignees = assigneeResolver.resolve(node, instance.startedByPersonId(), today);
        OffsetDateTime dueAt = node.slaMinutes() > 0
                ? OffsetDateTime.now().plusMinutes(node.slaMinutes()) : null;
        String title = "审批：" + node.key();
        if (assignees.isEmpty()) {
            store.insertException(instance.id(), null, "ASSIGNEE_UNRESOLVED",
                    "审批节点无法解析审批人：" + node.key(),
                    "{\"nodeKey\":\"" + node.key() + "\"}");
            store.insertTask(new WorkflowStore.NewTask(instance.id(), token.id(), node.key(),
                    instance.businessType(), instance.businessId(), "approval", title, null,
                    node.assigneeKind(), node.roleCode(), "[]", dueAt));
            store.updateTokenStatus(token.id(), "waiting");
            store.log(instance.id(), null, node.key(), "active", "waiting",
                    "assignee.unresolved", null, null);
            return;
        }
        for (UUID assignee : assignees) {
            UUID taskId = store.insertTask(new WorkflowStore.NewTask(instance.id(), token.id(),
                    node.key(), instance.businessType(), instance.businessId(), "approval",
                    title, assignee, node.assigneeKind(), node.roleCode(), "[]", dueAt));
            publishTaskCreated(taskId, instance, title, assignee, dueAt);
        }
        store.updateTokenStatus(token.id(), "waiting");
        store.log(instance.id(), null, node.key(), "active", "waiting",
                "task.created", null, null);
    }

    private static WorkflowSchema.Edge outcomeEdge(WorkflowSchema schema, String nodeKey,
                                                    String outcome) {
        List<WorkflowSchema.Edge> outgoing = schema.outgoing(nodeKey);
        for (WorkflowSchema.Edge edge : outgoing) {
            if (outcome.equals(edge.when())) {
                return edge;
            }
        }
        for (WorkflowSchema.Edge edge : outgoing) {
            if (edge.when().isBlank() && edge.condition() == null) {
                return edge;
            }
        }
        return null;
    }

    private void maybeComplete(WorkflowStore.InstanceRow instance) {
        if (store.countTokens(instance.id(), "active") == 0
                && store.countTokens(instance.id(), "waiting") == 0) {
            store.updateInstanceStatus(instance.id(), "completed", OffsetDateTime.now());
            store.log(instance.id(), null, null, "running", "completed", "complete", null, null);
        }
    }

    private void publishTaskCreated(UUID taskId, WorkflowStore.InstanceRow instance, String title,
                                    UUID assignee, OffsetDateTime dueAt) {
        if (RequestContextHolder.find().isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", taskId);
        payload.put("instance_id", instance.id());
        payload.put("business_type", instance.businessType());
        if (instance.businessId() != null) {
            payload.put("business_id", instance.businessId());
        }
        payload.put("title", title);
        payload.put("assignee_person_id", assignee);
        if (dueAt != null) {
            payload.put("due_at", dueAt.toString());
        }
        eventPublisher.publish("workflow.task.created.v1", payload);
    }

    /** 供实例服务在取消/撤回时读取运行期定义。 */
    List<WorkflowSchema.Edge> outgoingOf(WorkflowSchema schema, String nodeKey) {
        return new ArrayList<>(schema.outgoing(nodeKey));
    }
}
