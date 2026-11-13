package com.acme.platform.workflow;

import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.OutboxEventPublisher;
import com.acme.platform.platform.RequestContextHolder;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * SLA 扫描：80% 预警、到期标记超时并给处理人的直属主管生成升级任务。
 *
 * <p>任何 SLA 路径都不调用 approve；解析不到升级对象时只写异常，流程保持等待。
 */
@Service
public class WorkflowSlaService {

    private final WorkflowStore store;
    private final WorkflowTenantWork tenantWork;
    private final OrganizationScopeQueries organization;
    private final OutboxEventPublisher eventPublisher;

    public WorkflowSlaService(WorkflowStore store, WorkflowTenantWork tenantWork,
                              OrganizationScopeQueries organization,
                              OutboxEventPublisher eventPublisher) {
        this.store = store;
        this.tenantWork = tenantWork;
        this.organization = organization;
        this.eventPublisher = eventPublisher;
    }

    public int scan() {
        return tenantWork.forEachActiveTenant(context -> {
            store.markWarningTasks();
            int breached = 0;
            for (WorkflowStore.TaskRow task : store.breachedTasks(50)) {
                store.setSlaState(task.id(), "breached");
                store.insertException(task.instanceId(), task.id(), "SLA_BREACHED",
                        "审批任务超时：" + task.title(),
                        "{\"taskId\":\"" + task.id() + "\"}");
                if (task.assigneePersonId() != null) {
                    UUID manager = organization.managerPersonOf(task.assigneePersonId(),
                            LocalDate.now()).orElse(null);
                    if (manager != null) {
                        createEscalation(task, manager);
                    }
                }
                breached++;
            }
            return breached;
        }).stream().mapToInt(Integer::intValue).sum();
    }

    private void createEscalation(WorkflowStore.TaskRow task, UUID manager) {
        String title = "SLA 升级：" + task.title();
        UUID taskId = store.insertTask(new WorkflowStore.NewTask(null, null, null,
                "workflow.escalation", task.id(), "review", title, manager, "person", null,
                "[]", OffsetDateTime.now().plusDays(1)));
        if (RequestContextHolder.find().isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", taskId);
        payload.put("business_type", "workflow.escalation");
        payload.put("business_id", task.id());
        payload.put("title", title);
        payload.put("assignee_person_id", manager);
        payload.put("due_at", OffsetDateTime.now().plusDays(1).toString());
        eventPublisher.publish("workflow.task.created.v1", payload);
    }
}
