package com.acme.platform.worker.workflow;

import com.acme.platform.workflow.WorkflowSlaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** SLA 扫描：每分钟标记预警/超时并生成升级任务，永不自动批准。 */
@Component
public class WorkflowSlaWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSlaWorker.class);

    private final WorkflowSlaService slaService;

    public WorkflowSlaWorker(WorkflowSlaService slaService) {
        this.slaService = slaService;
    }

    @Scheduled(fixedDelayString = "${platform.workflow.sla-interval:1m}",
            initialDelayString = "${platform.workflow.sla-initial-delay:30s}")
    public void run() {
        int breached = slaService.scan();
        if (breached > 0) {
            log.info("工作流 SLA 超时 {} 个", breached);
        }
    }
}
