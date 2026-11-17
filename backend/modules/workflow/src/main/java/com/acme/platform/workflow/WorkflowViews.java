package com.acme.platform.workflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 工作流 API 的出参视图：控制器与前端共用同一组字段名。 */
public final class WorkflowViews {

    private WorkflowViews() {
    }

    public record TaskView(UUID id, UUID instanceId, String nodeKey, String businessType,
                           UUID businessId, String taskType, String title,
                           UUID assigneePersonId, String assigneeKind, String status,
                           String action, String comment, OffsetDateTime dueAt, String slaState,
                           OffsetDateTime createdAt) {
    }

    public record TokenView(UUID id, String nodeKey, String status) {
    }

    public record LogView(UUID id, UUID taskId, String nodeKey, String fromStatus,
                         String toStatus, String action, UUID actorPersonId, String comment,
                         OffsetDateTime occurredAt) {
    }

    public record InstanceView(UUID id, String definitionKey, int definitionVersion,
                               String businessType, UUID businessId, String businessKey,
                               String status, String variables, UUID startedByPersonId,
                               OffsetDateTime startedAt, OffsetDateTime endedAt) {
    }

    public record InstanceDetail(InstanceView instance, List<TokenView> tokens,
                                 List<TaskView> tasks, List<LogView> logs) {
    }

    public record ExceptionView(UUID id, UUID instanceId, UUID taskId, String code,
                                String message, String status, UUID resolvedBy,
                                String resolution, OffsetDateTime createdAt) {
    }

    static TaskView task(WorkflowStore.TaskRow row) {
        return new TaskView(row.id(), row.instanceId(), row.nodeKey(), row.businessType(),
                row.businessId(), row.taskType(), row.title(), row.assigneePersonId(),
                row.assigneeKind(), row.status(), row.action(), row.comment(), row.dueAt(),
                row.slaState(), row.createdAt());
    }

    static TokenView token(WorkflowStore.TokenRow row) {
        return new TokenView(row.id(), row.nodeKey(), row.status());
    }

    static LogView log(WorkflowStore.LogRow row) {
        return new LogView(row.id(), row.taskId(), row.nodeKey(), row.fromStatus(),
                row.toStatus(), row.action(), row.actorPersonId(), row.comment(),
                row.occurredAt());
    }

    static InstanceView instance(WorkflowStore.InstanceRow row) {
        return new InstanceView(row.id(), row.definitionKey(), row.definitionVersion(),
                row.businessType(), row.businessId(), row.businessKey(), row.status(),
                row.variables(), row.startedByPersonId(), row.startedAt(), row.endedAt());
    }

    static ExceptionView exception(WorkflowStore.ExceptionRow row) {
        return new ExceptionView(row.id(), row.instanceId(), row.taskId(), row.code(),
                row.message(), row.status(), row.resolvedBy(), row.resolution(), row.createdAt());
    }
}
