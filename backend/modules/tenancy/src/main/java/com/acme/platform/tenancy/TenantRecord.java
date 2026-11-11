package com.acme.platform.tenancy;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 租户主记录：状态机、套餐与各阶段时间点。
 *
 * <p>{@code id} 同时是其他业务表的 {@code tenant_id}，也是 RLS 的唯一隔离键。
 */
public record TenantRecord(
        UUID id,
        String slug,
        String name,
        TenantStatus status,
        String planCode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime frozenAt,
        String frozenReason,
        OffsetDateTime closingRequestedAt,
        OffsetDateTime coolingOffUntil,
        OffsetDateTime deletedAt) {
}
