package com.acme.platform.worker.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 一条待投递的领域事件，字段与 {@code contracts/events/envelope.v1.json} 的信封一一对应。
 *
 * @param payload 事件载荷原文（jsonb），消费者自行按事件名反序列化
 * @param attempts 本次投递前已累计的尝试次数（含本次）
 */
public record OutboxEvent(
        UUID eventId,
        String eventName,
        int schemaVersion,
        UUID tenantId,
        String correlationId,
        String traceId,
        String producer,
        OffsetDateTime occurredAt,
        String payload,
        int attempts) {
}
