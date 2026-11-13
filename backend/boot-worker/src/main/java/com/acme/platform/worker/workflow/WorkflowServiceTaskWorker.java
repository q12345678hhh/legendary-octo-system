package com.acme.platform.worker.workflow;

import com.acme.platform.workflow.WorkflowServiceTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 服务任务扫描：每 5 秒执行一次，失败按退避重试。 */
@Component
public class WorkflowServiceTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowServiceTaskWorker.class);

    private final WorkflowServiceTaskService serviceTaskService;

    public WorkflowServiceTaskWorker(WorkflowServiceTaskService serviceTaskService) {
        this.serviceTaskService = serviceTaskService;
    }

    @Scheduled(fixedDelayString = "${platform.workflow.service-task-interval:5s}",
            initialDelayString = "${platform.workflow.service-task-initial-delay:15s}")
    public void run() {
        int ran = serviceTaskService.runDue();
        if (ran > 0) {
            log.info("工作流服务任务执行 {} 个", ran);
        }
    }
}
