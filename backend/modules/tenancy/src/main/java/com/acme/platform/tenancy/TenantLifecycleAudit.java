package com.acme.platform.tenancy;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 租户生命周期审计条目。状态变更、受控导出、申诉与支持访问都会写入。
 */
public record TenantLifecycleAudit(
        UUID id,
        UUID tenantId,
        String action,
        TenantStatus fromStatus,
        TenantStatus toStatus,
        String actor,
        String reason,
        String ticketId,
        String correlationId,
        String traceId,
        OffsetDateTime occurredAt) {
}
