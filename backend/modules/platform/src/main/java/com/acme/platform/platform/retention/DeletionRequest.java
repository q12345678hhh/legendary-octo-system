package com.acme.platform.platform.retention;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 数据主体删除请求案件。
 */
public record DeletionRequest(
        UUID id,
        UUID tenantId,
        String subjectType,
        UUID subjectId,
        String subjectHash,
        String reason,
        String status,
        String decisionCode,
        String decisionNote,
        String requestedBy,
        OffsetDateTime requestedAt,
        String decidedBy,
        OffsetDateTime decidedAt,
        String executedBy,
        OffsetDateTime executedAt,
        OffsetDateTime propagatedAt) {
}
