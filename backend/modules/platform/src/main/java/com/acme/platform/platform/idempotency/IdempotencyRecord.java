package com.acme.platform.platform.idempotency;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 一条幂等记录：要么正在执行（in_progress），要么已缓存首次响应（completed）。
 */
public record IdempotencyRecord(
        UUID id,
        String requestHash,
        String status,
        Integer responseStatus,
        String responseContentType,
        String responseBody,
        OffsetDateTime expiresAt) {

    public boolean completed() {
        return "completed".equals(status);
    }
}
