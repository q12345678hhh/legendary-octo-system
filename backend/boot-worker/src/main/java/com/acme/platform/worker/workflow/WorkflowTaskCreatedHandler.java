package com.acme.platform.worker.workflow;

import com.acme.platform.collaboration.NotificationService;
import com.acme.platform.worker.outbox.OutboxEvent;
import com.acme.platform.worker.outbox.OutboxEventHandler;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 消费 {@code workflow.task.created.v1}：给处理人写站内通知。
 *
 * <p>通知按 task_id 资源去重；重复投递不会产生第二条未读。
 */
@Component
class WorkflowTaskCreatedHandler implements OutboxEventHandler {

    static final String EVENT_NAME = "workflow.task.created.v1";

    private static final Logger log = LoggerFactory.getLogger(WorkflowTaskCreatedHandler.class);

    private final NotificationService notifications;
    private final ObjectMapper objectMapper;

    WorkflowTaskCreatedHandler(NotificationService notifications, ObjectMapper objectMapper) {
        this.notifications = notifications;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventName() {
        return EVENT_NAME;
    }

    @Override
    public void handle(OutboxEvent event) {
        JsonNode payload = objectMapper.readTree(event.payload());
        String taskIdValue = payload.path("task_id").asString();
        String assigneeValue = payload.path("assignee_person_id").asString();
        if (taskIdValue.isBlank() || assigneeValue.isBlank()) {
            return;
        }
        UUID taskId = UUID.fromString(taskIdValue);
        UUID assignee = UUID.fromString(assigneeValue);
        String title = payload.path("title").asString();
        notifications.notifyPerson(assignee, "workflow.task", taskId,
                title.isBlank() ? "新的审批任务" : title, "请在审批箱处理");
        log.info("工作流任务通知已写入 taskId={} assignee={}", taskId, assignee);
    }
}
