package com.acme.platform.workflow;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 异常队列：查询与处置（重试服务任务、改派、补偿、关闭）。
 *
 * <p>处置动作不会自动批准流程；补偿失败会重新进入异常队列。
 */
@Service
public class WorkflowExceptionService {

    private final WorkflowStore store;
    private final TenantTransactions transactions;
    private final WorkflowEngine engine;

    public WorkflowExceptionService(WorkflowStore store, TenantTransactions transactions,
                                    WorkflowEngine engine) {
        this.store = store;
        this.transactions = transactions;
        this.engine = engine;
    }

    public List<WorkflowViews.ExceptionView> list() {
        return transactions.inTenant(context -> store.openExceptions().stream()
                .map(WorkflowViews::exception).toList());
    }

    public WorkflowViews.ExceptionView resolve(UUID exceptionId, UUID actorPersonId,
                                               String action, UUID assigneePersonId) {
        return transactions.inTenant(context -> {
            WorkflowStore.ExceptionRow row = store.exception(exceptionId)
                    .orElseThrow(() -> ApiException.notFound("WORKFLOW_EXCEPTION_NOT_FOUND",
                            "工作流异常不存在：" + exceptionId));
            if (!"open".equals(row.status())) {
                throw ApiException.conflict("WORKFLOW_EXCEPTION_RESOLVED", "该异常已处置");
            }
            String normalized = action == null ? "" : action;
            switch (normalized) {
                case "retry" -> {
                    if (row.taskId() != null) {
                        store.resetServiceTask(row.taskId());
                    }
                }
                case "reassign" -> {
                    if (assigneePersonId == null) {
                        throw ApiException.badRequest("WORKFLOW_REASSIGN_TARGET_REQUIRED",
                                "改派必须指定新的处理人");
                    }
                    if (row.taskId() == null || store.task(row.taskId()).isEmpty()) {
                        throw ApiException.badRequest("WORKFLOW_REASSIGN_TASK_MISSING",
                                "该异常没有可改派的任务");
                    }
                    store.updateTaskAssignee(row.taskId(), assigneePersonId, "person");
                }
                case "compensate" -> compensate(row);
                case "close" -> {
                    // 仅关闭异常，不改动流程
                }
                default -> throw ApiException.badRequest("WORKFLOW_EXCEPTION_ACTION_INVALID",
                        "处置动作只能是 retry/reassign/compensate/close");
            }
            String resolution = normalized
                    + (assigneePersonId == null ? "" : ":" + assigneePersonId);
            store.resolveException(exceptionId, actorPersonId, resolution);
            return store.exception(exceptionId).map(WorkflowViews::exception).orElseThrow();
        });
    }

    private void compensate(WorkflowStore.ExceptionRow row) {
        if (row.instanceId() == null || row.taskId() == null) {
            throw ApiException.badRequest("WORKFLOW_COMPENSATION_TARGET_MISSING",
                    "该异常没有可补偿的服务任务");
        }
        WorkflowStore.ServiceTaskRow service = store.serviceTask(row.taskId())
                .orElseThrow(() -> ApiException.badRequest(
                        "WORKFLOW_COMPENSATION_TARGET_MISSING", "找不到原服务任务"));
        WorkflowSchema schema = engine.schemaOf(row.instanceId());
        WorkflowSchema.Node node = schema.node(service.nodeKey());
        if (node == null || node.compensation() == null || node.compensation().isBlank()) {
            throw ApiException.badRequest("WORKFLOW_COMPENSATION_NOT_CONFIGURED",
                    "该节点没有配置补偿处理器");
        }
        store.insertServiceTask(row.instanceId(), service.tokenId(), service.nodeKey(),
                node.compensation(), row.instanceId() + ":" + service.id() + ":compensation",
                node.maxAttempts(), service.payload(), service.id());
        store.markServiceCompensated(service.id());
    }
}
