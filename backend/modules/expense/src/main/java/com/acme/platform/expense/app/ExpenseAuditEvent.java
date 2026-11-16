package com.acme.platform.expense.app;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 只追加的单据审计事件。
 */
public record ExpenseAuditEvent(
        UUID id,
        UUID documentId,
        String action,
        UUID actorPersonId,
        String actorKind,
        Map<String, Object> detail,
        OffsetDateTime occurredAt) {
}
