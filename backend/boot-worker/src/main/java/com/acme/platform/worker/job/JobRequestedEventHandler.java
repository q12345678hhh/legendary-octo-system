package com.acme.platform.worker.job;

import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.job.JobRecord;
import com.acme.platform.platform.job.JobStore;
import com.acme.platform.worker.outbox.OutboxEvent;
import com.acme.platform.worker.outbox.OutboxEventHandler;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 消费 {@code platform.job.requested.v1}，推进作业进度。
 *
 * <p>进度是分步写库的，每一步都重新检查取消标记：API 只能「请求取消」，
 * 真正停下来的是这里，避免强杀线程留下半写状态。
 *
 * <p>处理器必须幂等：事件至少投递一次，重复投递看到非 queued 状态就直接返回。
 */
@Component
class JobRequestedEventHandler implements OutboxEventHandler {

    static final String EVENT_NAME = "platform.job.requested.v1";

    private static final Logger log = LoggerFactory.getLogger(JobRequestedEventHandler.class);
    private static final int STEPS = 10;

    private final JobStore store;
    private final TenantTransactions transactions;
    private final PlatformMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Duration stepDelay;

    JobRequestedEventHandler(JobStore store,
                             TenantTransactions transactions,
                             PlatformMetrics metrics,
                             ObjectMapper objectMapper,
                             @Value("${platform.jobs.step-delay:300ms}") Duration stepDelay) {
        this.store = store;
        this.transactions = transactions;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.stepDelay = stepDelay;
    }

    @Override
    public String eventName() {
        return EVENT_NAME;
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        UUID jobId = UUID.fromString(objectMapper.readTree(event.payload()).path("job_id").asString());
        JobRecord job = transactions.inTenant(ctx -> store.find(jobId)).orElse(null);
        if (job == null) {
            log.warn("作业事件找不到作业 jobId={}（可能跨租户或已被清理）", jobId);
            return;
        }
        if (!"queued".equals(job.status())) {
            log.info("作业已处理，跳过重复事件 jobId={} status={}", jobId, job.status());
            return;
        }
        if ("fail-demo".equals(job.jobType())) {
            // 故意失败：用于验证重试耗尽后进入死信、以及安全回放链路
            throw new IllegalStateException("演示失败：作业类型 fail-demo");
        }

        transactions.inTenant(ctx -> {
            store.markRunning(jobId);
            return null;
        });

        for (int step = 1; step <= STEPS; step++) {
            Thread.sleep(stepDelay.toMillis());
            if (transactions.inTenant(ctx -> store.cancelRequested(jobId))) {
                transactions.inTenant(ctx -> {
                    store.cancel(jobId);
                    return null;
                });
                metrics.jobState("cancelled");
                log.info("作业已按请求取消 jobId={} progress={}", jobId, step * 10);
                return;
            }
            int progress = step * 10;
            transactions.inTenant(ctx -> {
                store.updateProgress(jobId, progress);
                return null;
            });
            log.debug("作业进度 jobId={} progress={}", jobId, progress);
        }

        transactions.inTenant(ctx -> {
            store.succeed(jobId, "{\"steps\":" + STEPS + "}");
            return null;
        });
        metrics.jobState("succeeded");
        log.info("作业完成 jobId={} jobType={}", jobId, job.jobType());
    }
}
