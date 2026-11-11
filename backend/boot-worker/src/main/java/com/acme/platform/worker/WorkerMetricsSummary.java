package com.acme.platform.worker;

import com.acme.platform.platform.PlatformMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 指标心跳：Worker 不暴露 Web 端口，按固定间隔把计数器快照写进日志。
 *
 * <p>这是 outbox、文件扫描与作业状态指标在本地/预发环境的取证入口；
 * 接入 OTLP 采集器后由采集器接管，日志心跳可下线。
 */
@Component
class WorkerMetricsSummary {

    private static final Logger log = LoggerFactory.getLogger(WorkerMetricsSummary.class);

    private final PlatformMetrics metrics;

    WorkerMetricsSummary(PlatformMetrics metrics) {
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${platform.metrics.summary-interval:15s}")
    void logSnapshot() {
        log.info("平台指标汇总 {}", metrics.snapshot());
    }
}
