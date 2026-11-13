package com.acme.platform.tenancy.retention;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 回收站条目。
 */
public record RetentionBinItem(
        UUID id,
        UUID tenantId,
        String dataClass,
        UUID objectId,
        String objectRef,
        String snapshot,
        String status,
        String deletedBy,
        OffsetDateTime deletedAt,
        OffsetDateTime purgeAfter,
        OffsetDateTime restoredAt,
        String restoredBy,
        OffsetDateTime purgedAt,
        OffsetDateTime anonymizedAt) {
}
