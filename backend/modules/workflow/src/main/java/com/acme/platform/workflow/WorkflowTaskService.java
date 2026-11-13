package com.acme.platform.workflow;

import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.OutboxEventPublisher;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 审批任务：认领、完成、转交、退回；同时承载工时/周报的直连审核任务。
 *
 * <p>业务回调失败时任务保持待办，异常队列记录原因；重复完成由
 * {@code status IN ('pending','claimed')} 条件更新兜底，不会重复写业务。
 */
@Service
public class WorkflowTaskService {

    /** 没有业务回调的通用流程。 */
    private static final Set<String> NO_HANDLER_TYPES = Set.of("generic", "workflow.generic");

    private final WorkflowStore store;
    private final TenantTransactions transactions;
    private final WorkflowEngine engine;
    private final List<WorkflowBusinessHandler> handlers;
    private final OutboxEventPublisher eventPublisher;
    private final OrganizationScopeQueries organization;
    private final ObjectMapper objectMapper;

    public WorkflowTaskService(WorkflowStore store, TenantTransactions transactions,
                               WorkflowEngine engine, List<WorkflowBusinessHandler> handlers,
                               OutboxEventPublisher eventPublisher,
                               OrganizationScopeQueries organization, ObjectMapper objectMapper) {
        this.store = store;
        this.transactions = transactions;
        this.engine = engine;
        this.handlers = handlers == null ? List.of() : handlers;
        this.eventPublisher = eventPublisher;
        this.organization = organization;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 查询

    public List<WorkflowViews.TaskView> inboxForPerson(UUID personId) {
        return transactions.inTenant(context -> store.openTasksForPerson(personId).stream()
                .map(WorkflowViews::task).toList());
    }

    public List<WorkflowViews.TaskView> inboxForScope(UUID personId, boolean tenantWide,
                                                      Collection<UUID> scopePersonIds) {
        return transactions.inTenant(context -> {
            List<WorkflowStore.TaskRow> rows;
            if (tenantWide) {
                rows = store.allOpenTasks();
            } else {
                Set<UUID> people = new LinkedHashSet<>();
                if (scopePersonIds != null) {
                    people.addAll(scopePersonIds);
                }
                if (personId != null) {
                    people.add(personId);
                }
                rows = store.openTasksForPeople(people);
            }
            return rows.stream().map(WorkflowViews::task).toList();
        });
    }

    // ------------------------------------------------------------------ 动作

    public WorkflowViews.TaskView claim(UUID taskId, UUID personId, boolean admin) {
        return transactions.inTenant(context -> {
            WorkflowStore.TaskRow task = requireTask(taskId);
            if (!"pending".equals(task.status())) {
                throw ApiException.conflict("WORKFLOW_TASK_NOT_PENDING",
                        "只有待办任务可以认领，当前状态：" + task.status());
            }
            if (!admin && task.assigneePersonId() != null
                    && !task.assigneePersonId().equals(personId)) {
                throw ApiException.forbidden("WORKFLOW_NOT_ASSIGNEE", "只能认领分配给自己的任务");
            }
            if (task.assigneePersonId() == null && !admin) {
                throw ApiException.forbidden("WORKFLOW_UNASSIGNED", "未分配任务只能由管理员处置");
            }
            store.claimTask(taskId, personId);
            return WorkflowViews.task(store.task(taskId).orElseThrow());
        });
    }

    public WorkflowViews.TaskView complete(UUID taskId, UUID personId, String action,
                                           String comment, boolean admin) {
        Result result = transactions.inTenant(context ->
                doComplete(taskId, personId, action, comment, admin));
        if (result.errorCode() != null) {
            throw ApiException.conflict(result.errorCode(), result.errorDetail());
        }
        return result.view();
    }

    public WorkflowViews.TaskView transfer(UUID taskId, UUID personId, UUID targetPersonId,
                                           String comment, boolean admin) {
        if (targetPersonId == null) {
            throw ApiException.badRequest("WORKFLOW_TRANSFER_TARGET_REQUIRED", "缺少转交目标");
        }
        return transactions.inTenant(context -> {
            WorkflowStore.TaskRow task = requireTask(taskId);
            if (!"pending".equals(task.status()) && !"claimed".equals(task.status())) {
                throw ApiException.conflict("WORKFLOW_TASK_NOT_OPEN", "任务已结束，不能转交");
            }
            if (!admin && !personId.equals(task.assigneePersonId())
                    && !personId.equals(task.claimedBy())) {
                throw ApiException.forbidden("WORKFLOW_NOT_ASSIGNEE", "只能转交自己处理的任务");
            }
            if (!organization.activePerson(targetPersonId, LocalDate.now())) {
                throw ApiException.badRequest("WORKFLOW_TRANSFER_TARGET_INVALID",
                        "转交目标不是当前在职人员");
            }
            store.markTaskTransferred(taskId, comment, personId);
            UUID newTaskId = store.insertTask(new WorkflowStore.NewTask(task.instanceId(),
                    task.tokenId(), task.nodeKey(), task.businessType(), task.businessId(),
                    task.taskType(), task.title(), targetPersonId, "person",
                    task.assigneeRole(), task.candidates(), task.dueAt()));
            store.log(task.instanceId(), task.id(), task.nodeKey(), task.status(), "transferred",
                    "transfer", personId, comment);
            publishTaskCreated(newTaskId, task.businessType(), task.businessId(), task.title(),
                    targetPersonId, task.dueAt(), task.instanceId());
            return WorkflowViews.task(store.task(newTaskId).orElseThrow());
        });
    }

    /** 工时/周报直连审核任务：同一业务对象只建一条。 */
    public WorkflowViews.TaskView openDirectTask(String businessType, UUID businessId,
                                                 String taskType, String title,
                                                 String assigneeKind, UUID assigneePersonId,
                                                 OffsetDateTime dueAt) {
        if (businessId == null) {
            return null;
        }
        return transactions.inTenant(context -> {
            WorkflowStore.TaskRow existing = store
                    .directTask(businessType, businessId, taskType).orElse(null);
            if (existing != null) {
                return WorkflowViews.task(existing);
            }
            UUID id = store.insertTask(new WorkflowStore.NewTask(null, null, null, businessType,
                    businessId, taskType, title, assigneePersonId,
                    assigneeKind == null ? "direct" : assigneeKind, null, "[]", dueAt));
            publishTaskCreated(id, businessType, businessId, title, assigneePersonId, dueAt,
                    null);
            return WorkflowViews.task(store.task(id).orElseThrow());
        });
    }

    /** 工时/周报模块自己完成审核后调用：只关闭任务，不回调业务。 */
    public void markDecided(String businessType, UUID businessId, UUID actorPersonId,
                            String action, String comment) {
        if (businessId == null) {
            return;
        }
        transactions.inTenant(context -> {
            store.directTask(businessType, businessId, "review").ifPresent(task -> {
                if ("pending".equals(task.status()) || "claimed".equals(task.status())) {
                    if ("approve".equals(action)) {
                        store.completeTask(task.id(), "approve", comment, actorPersonId);
                    } else {
                        store.markTaskReturned(task.id(), comment, actorPersonId);
                    }
                }
            });
            return null;
        });
    }

    // ------------------------------------------------------------------ 内部

    private record Result(WorkflowViews.TaskView view, String errorCode, String errorDetail) {
    }

    private record HandlerResult(String code, String message) {
        static HandlerResult ok() {
            return new HandlerResult(null, null);
        }
    }

    private Result doComplete(UUID taskId, UUID personId, String action, String comment,
                              boolean admin) {
        WorkflowStore.TaskRow task = requireTask(taskId);
        if (!"pending".equals(task.status()) && !"claimed".equals(task.status())) {
            throw ApiException.conflict("WORKFLOW_TASK_NOT_OPEN",
                    "任务已结束，不能重复处理，当前状态：" + task.status());
        }
        if (!admin) {
            if (task.assigneePersonId() == null) {
                throw ApiException.forbidden("WORKFLOW_UNASSIGNED", "未分配任务只能由管理员处置");
            }
            if (!task.assigneePersonId().equals(personId)
                    && !Objects.equals(task.claimedBy(), personId)) {
                throw ApiException.forbidden("WORKFLOW_NOT_ASSIGNEE", "只能处理分配给自己的任务");
            }
        }
        String normalized = switch (action == null ? "" : action) {
            case "approve" -> "approve";
            case "return" -> "return";
            default -> throw ApiException.badRequest("WORKFLOW_TASK_ACTION_INVALID",
                    "审批动作只能是 approve 或 return");
        };
        if ("return".equals(normalized) && (comment == null || comment.isBlank())) {
            throw ApiException.badRequest("WORKFLOW_RETURN_COMMENT_REQUIRED", "退回必须填写原因");
        }

        if (task.instanceId() == null) {
            if ("approve".equals(normalized)) {
                store.completeTask(taskId, "approve", comment, personId);
            } else {
                store.markTaskReturned(taskId, comment, personId);
            }
            return new Result(WorkflowViews.task(store.task(taskId).orElseThrow()), null, null);
        }

        WorkflowSchema schema = engine.schemaOf(task.instanceId());
        WorkflowSchema.Node node = schema.node(task.nodeKey());
        HandlerResult handler = callHandler(task, normalized, personId, comment);
        if (handler.code() != null) {
            store.insertException(task.instanceId(), task.id(), handler.code(), handler.message(),
                    "{\"taskId\":\"" + task.id() + "\"}");
            return new Result(null, "WORKFLOW_" + handler.code(), handler.message());
        }

        if ("return".equals(normalized)) {
            store.markTaskReturned(taskId, comment, personId);
            for (WorkflowStore.TaskRow other : store.tasksForInstance(task.instanceId())) {
                if (!other.id().equals(taskId)
                        && ("pending".equals(other.status()) || "claimed".equals(other.status()))) {
                    store.cancelTask(other.id(), "实例已退回");
                }
            }
            store.cancelPendingTimers(task.instanceId());
            store.cancelOpenTokens(task.instanceId());
            store.updateInstanceStatus(task.instanceId(), "returned", OffsetDateTime.now());
            store.log(task.instanceId(), taskId, task.nodeKey(), "waiting", "returned",
                    "return", personId, comment);
            return new Result(WorkflowViews.task(store.task(taskId).orElseThrow()), null, null);
        }

        store.completeTask(taskId, "approve", comment, personId);
        store.log(task.instanceId(), taskId, task.nodeKey(), "waiting", "completed",
                "approve", personId, comment);
        String mode = node == null ? "any" : node.approvalMode();
        if ("all".equals(mode)) {
            if (store.countPendingTasksAtNode(task.instanceId(), task.nodeKey()) == 0
                    && task.tokenId() != null) {
                engine.outcome(task.instanceId(), task.tokenId(), "approved");
            }
        } else {
            for (WorkflowStore.TaskRow sibling : store.openTasksAtNode(task.instanceId(),
                    task.nodeKey())) {
                store.cancelTask(sibling.id(), "会签已完成");
            }
            if (task.tokenId() != null) {
                engine.outcome(task.instanceId(), task.tokenId(), "approved");
            }
        }
        return new Result(WorkflowViews.task(store.task(taskId).orElseThrow()), null, null);
    }

    private HandlerResult callHandler(WorkflowStore.TaskRow task, String action, UUID actor,
                                      String comment) {
        if (NO_HANDLER_TYPES.contains(task.businessType())) {
            return HandlerResult.ok();
        }
        Optional<WorkflowBusinessHandler> handler = handlers.stream()
                .filter(item -> item.businessType().equals(task.businessType()))
                .findFirst();
        if (handler.isEmpty()) {
            return new HandlerResult("HANDLER_MISSING",
                    "没有注册业务处理器：" + task.businessType());
        }
        try {
            if ("approve".equals(action)) {
                handler.get().approved(task.businessId(), actor, comment);
            } else {
                handler.get().returned(task.businessId(), actor, comment);
            }
            return HandlerResult.ok();
        } catch (RuntimeException error) {
            return new HandlerResult("BUSINESS_ACTION_FAILED",
                    "业务写入失败：" + error.getMessage());
        }
    }

    private WorkflowStore.TaskRow requireTask(UUID taskId) {
        return store.task(taskId).orElseThrow(() ->
                ApiException.notFound("WORKFLOW_TASK_NOT_FOUND", "审批任务不存在：" + taskId));
    }

    private void publishTaskCreated(UUID taskId, String businessType, UUID businessId,
                                    String title, UUID assignee, OffsetDateTime dueAt,
                                    UUID instanceId) {
        if (RequestContextHolder.find().isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", taskId);
        if (instanceId != null) {
            payload.put("instance_id", instanceId);
        }
        payload.put("business_type", businessType);
        if (businessId != null) {
            payload.put("business_id", businessId);
        }
        payload.put("title", title);
        if (assignee != null) {
            payload.put("assignee_person_id", assignee);
        }
        if (dueAt != null) {
            payload.put("due_at", dueAt.toString());
        }
        eventPublisher.publish("workflow.task.created.v1", payload);
    }
}
