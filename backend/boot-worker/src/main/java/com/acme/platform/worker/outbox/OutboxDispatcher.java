package com.acme.platform.worker.outbox;

import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import java.util.UUID;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 轮询 outbox 并把事件交给对应处理器。
 *
 * <p>投递语义是「至少一次」：处理器成功返回才置为 published，抛异常则按退避重试，
 * 因此处理器必须自己保证幂等。
 *
 * <p>投递时把事件里的租户与 trace 绑进 {@link RequestContextHolder} 与 MDC：
 * 这样 Worker 里的租户表读写有 RLS 上下文，日志也能与页面、API 对齐到同一条 trace。
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxStore store;
    private final OutboxProperties properties;
    private final PlatformMetrics metrics;
    private final Map<String, OutboxEventHandler> handlers;

    OutboxDispatcher(OutboxStore store, OutboxProperties properties, PlatformMetrics metrics,
                     List<OutboxEventHandler> handlers) {
        this.store = store;
        this.properties = properties;
        this.metrics = metrics;
        this.handlers = indexByEventName(handlers);
    }

    @Scheduled(fixedDelayString = "${platform.outbox.poll-interval:2s}")
    public void poll() {
        int dispatched = dispatchOnce();
        if (dispatched > 0) {
            log.debug("本轮投递 {} 条事件", dispatched);
        }
    }

    /**
     * 处理一轮，返回认领到的事件条数。单独暴露出来便于单元测试直接驱动。
     */
    public int dispatchOnce() {
        List<OutboxEvent> events = store.claim(properties.batchSize());
        for (OutboxEvent event : events) {
            deliver(event);
        }
        return events.size();
    }

    private void deliver(OutboxEvent event) {
        String traceId = event.traceId() == null || event.traceId().isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : event.traceId();
        RequestContextHolder.set(new RequestContext(event.tenantId(), null,
                event.correlationId(), traceId));
        MDC.put("correlation_id", event.correlationId());
        MDC.put("trace_id", traceId);
        if (event.tenantId() != null) {
            MDC.put("tenant_id", event.tenantId().toString());
        }
        try {
            deliverWithContext(event, traceId);
        } finally {
            MDC.remove("correlation_id");
            MDC.remove("trace_id");
            MDC.remove("tenant_id");
            RequestContextHolder.clear();
        }
    }

    private void deliverWithContext(OutboxEvent event, String traceId) {
        OutboxEventHandler handler = handlers.get(event.eventName());
        if (handler == null) {
            // 没有消费者就先留着重试，等处理器上线后还能补投；重试耗尽才进 dead
            fail(event, "未注册处理器：" + event.eventName());
            return;
        }
        try {
            handler.handle(event);
            store.markPublished(event.eventId());
            metrics.outboxDispatched();
            log.info("事件已消费 event={} eventId={} tenantId={} correlationId={} traceId={}",
                    event.eventName(), event.eventId(), event.tenantId(),
                    event.correlationId(), traceId);
        } catch (Exception ex) {
            fail(event, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void fail(OutboxEvent event, String error) {
        store.markFailed(event.eventId(), error, properties.maxAttempts(), properties.retryDelay());
        metrics.outboxFailed();
        log.warn("事件投递失败 event={} eventId={} attempts={} traceId={} error={}",
                event.eventName(), event.eventId(), event.attempts(), event.traceId(), error);
    }

    private static Map<String, OutboxEventHandler> indexByEventName(List<OutboxEventHandler> handlers) {
        Map<String, OutboxEventHandler> byName = new HashMap<>();
        for (OutboxEventHandler handler : handlers) {
            OutboxEventHandler existing = byName.putIfAbsent(handler.eventName(), handler);
            if (existing != null) {
                throw new IllegalStateException("事件名 " + handler.eventName() + " 注册了多个处理器："
                        + existing.getClass().getName() + " 与 " + handler.getClass().getName());
            }
        }
        return Map.copyOf(byName);
    }
}
