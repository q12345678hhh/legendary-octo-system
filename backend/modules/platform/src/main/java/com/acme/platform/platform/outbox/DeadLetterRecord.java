package com.acme.platform.platform.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 死信视图：只暴露排障需要的字段，不含事件载荷正文。
 */
public record DeadLetterRecord(
        UUID eventId,
        String eventName,
        int attempts,
        String lastError,
        OffsetDateTime occurredAt,
        OffsetDateTime lastAttemptAt,
        String correlationId,
        String traceId) {
}
