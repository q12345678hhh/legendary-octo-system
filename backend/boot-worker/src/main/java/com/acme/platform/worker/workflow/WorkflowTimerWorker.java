package com.acme.platform.worker.workflow;

import com.acme.platform.workflow.WorkflowTimerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 到期定时器扫描：每 5 秒认领一次，推进对应令牌。 */
@Component
public class WorkflowTimerWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTimerWorker.class);

    private final WorkflowTimerService timerService;

    public WorkflowTimerWorker(WorkflowTimerService timerService) {
        this.timerService = timerService;
    }

    @Scheduled(fixedDelayString = "${platform.workflow.timer-interval:5s}",
            initialDelayString = "${platform.workflow.timer-initial-delay:10s}")
    public void run() {
        int fired = timerService.fireDue();
        if (fired > 0) {
            log.info("工作流定时器推进 {} 个", fired);
        }
    }
}
