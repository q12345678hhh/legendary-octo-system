package com.acme.platform.worker.outbox;

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
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxStore store;
    private final OutboxProperties properties;
    private final Map<String, OutboxEventHandler> handlers;

    OutboxDispatcher(OutboxStore store, OutboxProperties properties, List<OutboxEventHandler> handlers) {
        this.store = store;
        this.properties = properties;
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
        OutboxEventHandler handler = handlers.get(event.eventName());
        if (handler == null) {
            // 没有消费者就先留着重试，等处理器上线后还能补投；重试耗尽才进 dead
            fail(event, "未注册处理器：" + event.eventName());
            return;
        }
        try {
            handler.handle(event);
            store.markPublished(event.eventId());
            log.info("事件已消费 event={} eventId={} tenantId={} correlationId={} traceId={}",
                    event.eventName(), event.eventId(), event.tenantId(),
                    event.correlationId(), event.traceId());
        } catch (Exception ex) {
            fail(event, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void fail(OutboxEvent event, String error) {
        store.markFailed(event.eventId(), error, properties.maxAttempts(), properties.retryDelay());
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
