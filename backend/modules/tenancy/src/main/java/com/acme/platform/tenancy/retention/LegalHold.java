package com.acme.platform.tenancy.retention;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 生效中的法定留置。{@code objectId} 为空表示整类留置。
 */
public record LegalHold(
        UUID id,
        UUID tenantId,
        String dataClass,
        UUID objectId,
        String reason,
        String createdBy,
        OffsetDateTime createdAt) {
}
