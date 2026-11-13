package com.acme.platform.collaboration;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 通用任务、看板状态与里程碑。 */
@Service
public class TaskService {

    private static final Set<String> STATUSES = Set.of("todo", "doing", "done");

    private final CollaborationStore store;
    private final TenantTransactions transactions;
    private final ProjectService projects;

    public TaskService(CollaborationStore store, TenantTransactions transactions,
                       ProjectService projects) {
        this.store = store;
        this.transactions = transactions;
        this.projects = projects;
    }

    public List<CollaborationStore.Task> list(CollabViewer viewer, UUID projectId, String status,
                                              int limit) {
        if (projectId != null) {
            projects.requireReadable(viewer, projectId);
        }
        if (status != null && !status.isBlank() && !STATUSES.contains(status.strip())) {
            throw ApiException.badRequest("TASK_STATUS_INVALID", "任务状态取值非法");
        }
        String normalized = status == null || status.isBlank() ? null : status.strip();
        return transactions.inTenant(context -> store.tasks(viewer, projectId, normalized, limit));
    }

    public CollaborationStore.Task requireReadable(CollabViewer viewer, UUID taskId) {
        return transactions.inTenant(context -> {
            CollaborationStore.Task task = store.task(taskId).orElseThrow(() ->
                    ApiException.notFound("TASK_NOT_FOUND", "任务不存在"));
            if (!store.taskReadable(viewer, taskId)) {
                throw ApiException.forbidden("TASK_FORBIDDEN", "无权访问该任务");
            }
            return task;
        });
    }

    public CollaborationStore.Task create(CollabViewer viewer, UUID projectId, UUID orgUnitId,
                                          UUID milestoneId, String title, String description,
                                          UUID assigneePersonId, LocalDate dueDate) {
        if (projectId == null && orgUnitId == null) {
            throw ApiException.badRequest("TASK_SCOPE_REQUIRED", "任务必须归属项目或部门");
        }
        if (projectId != null) {
            projects.requireReadable(viewer, projectId);
        } else if (!viewer.seesUnit(orgUnitId)) {
            throw ApiException.forbidden("TASK_UNIT_FORBIDDEN", "只能在本部门创建任务");
        }
        String safeTitle = CollabValidation.requireText(title, 200, "TASK_TITLE", "任务标题");
        String safeDescription = CollabValidation.optionalText(description, 5000,
                "TASK_DESCRIPTION", "任务说明");
        return transactions.inTenant(context -> {
            UUID taskId = store.insertTask(projectId, orgUnitId, milestoneId, safeTitle,
                    safeDescription, assigneePersonId, dueDate, viewer.personId());
            notifyAssignee(projectId, taskId, safeTitle, assigneePersonId, viewer.personId());
            return store.task(taskId).orElseThrow();
        });
    }

    public CollaborationStore.Task updateStatus(CollabViewer viewer, UUID taskId, String status) {
        if (status == null || !STATUSES.contains(status.strip())) {
            throw ApiException.badRequest("TASK_STATUS_INVALID", "任务状态取值非法");
        }
        requireReadable(viewer, taskId);
        return transactions.inTenant(context -> {
            store.updateTaskStatus(taskId, status.strip());
            return store.task(taskId).orElseThrow();
        });
    }

    public List<CollaborationStore.Milestone> milestones(CollabViewer viewer, UUID projectId) {
        projects.requireReadable(viewer, projectId);
        return transactions.inTenant(context -> store.milestones(projectId));
    }

    public CollaborationStore.Milestone createMilestone(CollabViewer viewer, UUID projectId,
                                                        String name, LocalDate dueDate) {
        projects.requireReadable(viewer, projectId);
        String safeName = CollabValidation.requireText(name, 120, "MILESTONE_NAME", "里程碑名称");
        return transactions.inTenant(context -> {
            UUID id = store.insertMilestone(projectId, safeName, dueDate);
            return store.milestones(projectId).stream()
                    .filter(milestone -> milestone.id().equals(id))
                    .findFirst()
                    .orElseThrow();
        });
    }

    private void notifyAssignee(UUID projectId, UUID taskId, String title, UUID assignee,
                                UUID creator) {
        if (assignee == null || assignee.equals(creator)) {
            return;
        }
        // 收件人必须仍是项目成员，避免任务通知越过项目边界
        if (projectId == null || !store.projectMembers(projectId).contains(assignee)) {
            return;
        }
        store.insertNotification(assignee, "task", taskId, "你被指派任务：" + title,
                "任务来自项目协作");
    }
}
