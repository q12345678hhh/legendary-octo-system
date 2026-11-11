package com.acme.platform.platform;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 平台指标：名称与标签即运维契约，改动需要同步 docs/runbooks/observability.md 与仪表板。
 *
 * <p>这里只计数与打标签，不记录请求正文、文件内容或主体可识别信息，
 * 避免指标后端变成第二个日志泄露面。
 */
@Component
public class PlatformMetrics {

    private final MeterRegistry registry;

    /** 汇总日志里展示的计数器，顺序即阅读顺序。 */
    private static final List<String> SNAPSHOT_METERS = List.of(
            "platform.http.requests",
            "platform.idempotency",
            "platform.jobs.submitted",
            "platform.jobs.state",
            "platform.files.scanned",
            "platform.outbox.dispatched",
            "platform.outbox.failed",
            "platform.outbox.replayed");

    public PlatformMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void httpRequest(int status) {
        registry.counter("platform.http.requests", "status", Integer.toString(status)).increment();
    }

    public void idempotency(String result) {
        registry.counter("platform.idempotency", "result", result).increment();
    }

    /**
     * 新建作业计数。名字不能用 {@code platform.jobs.created}：Micrometer 的 Prometheus
     * 命名会把 {@code created} 当成保留后缀剥掉，导出的名字会变成容易误解的
     * {@code platform_jobs_total}，所以这里用 {@code submitted}。
     */
    public void jobSubmitted() {
        registry.counter("platform.jobs.submitted").increment();
    }

    public void jobState(String state) {
        registry.counter("platform.jobs.state", "state", state).increment();
    }

    public void fileScanned(String result) {
        registry.counter("platform.files.scanned", "result", result).increment();
    }

    public void outboxDispatched() {
        registry.counter("platform.outbox.dispatched").increment();
    }

    public void outboxFailed() {
        registry.counter("platform.outbox.failed").increment();
    }

    public void outboxReplayed() {
        registry.counter("platform.outbox.replayed").increment();
    }

    /**
     * 当前进程的计数器快照（按名字求和，不展开标签）。
     *
     * <p>Worker 没有 Web 端口，接入 OTLP 之前用它把指标写进周期汇总日志；
     * API 侧仍以 {@code /actuator/prometheus} 为准。
     */
    public String snapshot() {
        List<String> parts = new ArrayList<>(SNAPSHOT_METERS.size());
        for (String name : SNAPSHOT_METERS) {
            double total = registry.find(name).counters().stream()
                    .mapToDouble(Counter::count)
                    .sum();
            parts.add(name + "=" + (long) total);
        }
        return String.join(" ", parts);
    }
}
