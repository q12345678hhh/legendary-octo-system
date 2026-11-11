package com.acme.platform.worker.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * WP01-02 的冒烟消费者：把 {@code platform.smoke.ping.v1} 打进日志，
 * 用来证明「写 outbox → Worker 认领 → 投递 → trace_id 带出来」这条链路是通的。
 * 真正的业务消费者在后续工作包里按事件名逐个补上。
 */
@Component
class SmokePingEventHandler implements OutboxEventHandler {

    static final String EVENT_NAME = "platform.smoke.ping.v1";

    private static final Logger log = LoggerFactory.getLogger(SmokePingEventHandler.class);

    @Override
    public String eventName() {
        return EVENT_NAME;
    }

    @Override
    public void handle(OutboxEvent event) {
        log.info("冒烟事件已收到 eventId={} tenantId={} correlationId={} traceId={} occurredAt={} payload={}",
                event.eventId(), event.tenantId(), event.correlationId(),
                event.traceId(), event.occurredAt(), event.payload());
    }
}
