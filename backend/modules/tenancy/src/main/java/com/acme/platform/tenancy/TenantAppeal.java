package com.acme.platform.tenancy;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 冻结或注销冷静期内的员工申诉。
 */
public record TenantAppeal(
        UUID id,
        UUID tenantId,
        String subjectId,
        String reason,
        String status,
        OffsetDateTime submittedAt) {
}
