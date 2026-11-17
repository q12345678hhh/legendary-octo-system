package com.acme.platform.workflow;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 流程实例：启动（business_key 幂等）、撤回、取消、详情。
 *
 * <p>撤回仅限发起人且尚无任何审批通过；取消需要 {@code workflow.instance.admin}，
 * 两者都会关闭未完成任务与定时器，并对已成功的服务任务发起补偿。
 */
@Service
public class WorkflowInstanceService {

    private final WorkflowStore store;
    private final TenantTransactions transactions;
    private final WorkflowEngine engine;
    private final ObjectMapper objectMapper;

    public WorkflowInstanceService(WorkflowStore store, TenantTransactions transactions,
                                   WorkflowEngine engine, ObjectMapper objectMapper) {
        this.store = store;
        this.transactions = transactions;
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    public WorkflowViews.InstanceView start(String definitionKey, String businessType,
                                            UUID businessId, String businessKey,
                                            Map<String, Object> variables, UUID startedBy) {
        if (definitionKey == null || definitionKey.isBlank()) {
            throw ApiException.badRequest("WORKFLOW_KEY_REQUIRED", "缺少工作流定义 key");
        }
        if (businessType == null || businessType.isBlank()) {
            throw ApiException.badRequest("WORKFLOW_BUSINESS_TYPE_REQUIRED", "缺少业务类型");
        }
        if (startedBy == null) {
            throw ApiException.forbidden("PERSON_NOT_BOUND", "当前账号未绑定员工");
        }
        String serialized = variables == null ? "{}"
                : objectMapper.writeValueAsString(variables);
        return transactions.inTenant(context -> {
            if (businessKey != null && !businessKey.isBlank()) {
                WorkflowStore.InstanceRow existing = store
                        .instanceByBusinessKey(businessType, businessKey).orElse(null);
                if (existing != null) {
                    return WorkflowViews.instance(existing);
                }
            }
            WorkflowStore.DefinitionRow definition = store.publishedDefinition(definitionKey)
                    .orElseThrow(() -> ApiException.badRequest(
                            "WORKFLOW_DEFINITION_NOT_PUBLISHED",
                            "没有已发布的工作流定义：" + definitionKey));
            UUID instanceId = store.insertInstance(definition.id(), definition.version(),
                    definition.key(), businessType, businessId, businessKey, serialized, startedBy);
            UUID tokenId = store.insertToken(instanceId, "start", "active");
            store.log(instanceId, null, "start", null, "running", "start", startedBy, null);
            engine.advance(instanceId, tokenId);
            return store.instance(instanceId).map(WorkflowViews::instance).orElseThrow();
        });
    }

    public WorkflowViews.InstanceView withdraw(UUID instanceId, UUID personId) {
        return transactions.inTenant(context -> {
            WorkflowStore.InstanceRow instance = store.lockInstance(instanceId);
            if (!"running".equals(instance.status())) {
                throw ApiException.conflict("WORKFLOW_NOT_RUNNING",
                        "只有运行中的实例可以撤回，当前状态：" + instance.status());
            }
            if (!instance.startedByPersonId().equals(personId)) {
                throw ApiException.forbidden("WORKFLOW_NOT_INITIATOR", "只有发起人可以撤回");
            }
            if (store.countCompletedApprovals(instanceId) > 0) {
                throw ApiException.conflict("WORKFLOW_ALREADY_APPROVED",
                        "已有审批通过，不能撤回");
            }
            terminate(instance, "withdrawn", personId);
            return store.instance(instanceId).map(WorkflowViews::instance).orElseThrow();
        });
    }

    public WorkflowViews.InstanceView cancel(UUID instanceId, UUID actorPersonId) {
        return transactions.inTenant(context -> {
            WorkflowStore.InstanceRow instance = store.lockInstance(instanceId);
            if (!"running".equals(instance.status())) {
                throw ApiException.conflict("WORKFLOW_NOT_RUNNING",
                        "只有运行中的实例可以取消，当前状态：" + instance.status());
            }
            terminate(instance, "cancelled", actorPersonId);
            return store.instance(instanceId).map(WorkflowViews::instance).orElseThrow();
        });
    }

    public WorkflowViews.InstanceDetail detail(UUID instanceId) {
        return transactions.inTenant(context -> {
            WorkflowStore.InstanceRow instance = store.instance(instanceId)
                    .orElseThrow(() -> ApiException.notFound("WORKFLOW_INSTANCE_NOT_FOUND",
                            "流程实例不存在：" + instanceId));
            return new WorkflowViews.InstanceDetail(
                    WorkflowViews.instance(instance),
                    store.tokens(instanceId).stream().map(WorkflowViews::token).toList(),
                    store.tasksForInstance(instanceId).stream().map(WorkflowViews::task).toList(),
                    store.logs(instanceId).stream().map(WorkflowViews::log).toList());
        });
    }

    public List<WorkflowViews.InstanceView> startedBy(UUID personId) {
        return transactions.inTenant(context -> store.instancesStartedBy(personId).stream()
                .map(WorkflowViews::instance).toList());
    }

    public boolean hasPublished(String key) {
        return transactions.inTenant(context -> store.publishedDefinition(key).isPresent());
    }

    public WorkflowStore.InstanceRow requireInstance(UUID instanceId) {
        return transactions.inTenant(context -> store.instance(instanceId)
                .orElseThrow(() -> ApiException.notFound("WORKFLOW_INSTANCE_NOT_FOUND",
                        "流程实例不存在：" + instanceId)));
    }

    private void terminate(WorkflowStore.InstanceRow instance, String status, UUID actor) {
        for (WorkflowStore.TaskRow task : store.tasksForInstance(instance.id())) {
            if ("pending".equals(task.status()) || "claimed".equals(task.status())) {
                store.cancelTask(task.id(), "实例" + status);
            }
        }
        store.cancelPendingTimers(instance.id());
        store.cancelOpenTokens(instance.id());
        compensate(instance.id());
        store.updateInstanceStatus(instance.id(), status, OffsetDateTime.now());
        store.log(instance.id(), null, null, "running", status, status, actor, null);
    }

    private void compensate(UUID instanceId) {
        WorkflowSchema schema = engine.schemaOf(instanceId);
        for (WorkflowStore.ServiceTaskRow task : store.succeededCompensatable(instanceId)) {
            WorkflowSchema.Node node = schema.node(task.nodeKey());
            if (node == null || node.compensation() == null || node.compensation().isBlank()) {
                continue;
            }
            store.insertServiceTask(instanceId, task.tokenId(), task.nodeKey(),
                    node.compensation(), instanceId + ":" + task.id() + ":compensation",
                    node.maxAttempts(), task.payload(), task.id());
            store.markServiceCompensated(task.id());
        }
    }
}
