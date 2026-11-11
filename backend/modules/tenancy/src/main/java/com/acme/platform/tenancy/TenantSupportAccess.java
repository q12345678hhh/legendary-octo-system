package com.acme.platform.tenancy;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 平台运营对租户的一次支持访问授权。
 *
 * <p>只读、限时、需独立审批；{@link #banner()} 返回给前端展示醒目标识。
 */
public record TenantSupportAccess(
        UUID id,
        UUID tenantId,
        String ticketId,
        String operatorId,
        String reason,
        int requestedMinutes,
        String scope,
        SupportAccessStatus status,
        OffsetDateTime requestedAt,
        String approvedBy,
        OffsetDateTime approvedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        OffsetDateTime lastUsedAt) {

    public boolean activeAt(OffsetDateTime now) {
        return status == SupportAccessStatus.APPROVED
                && revokedAt == null
                && expiresAt != null
                && expiresAt.isAfter(now);
    }

    public SupportAccessBanner banner(OffsetDateTime now) {
        long remainingSeconds = expiresAt == null ? 0 : Math.max(0, expiresAt.toEpochSecond() - now.toEpochSecond());
        return new SupportAccessBanner(
                ticketId,
                operatorId,
                expiresAt,
                remainingSeconds,
                true,
                "平台支持访问中：工单 " + ticketId + "，只读，所有访问均记录审计");
    }

    /** 醒目标识：前端必须常驻展示，不能折叠或仅放入通知中心。 */
    public record SupportAccessBanner(
            String ticketId,
            String operatorId,
            OffsetDateTime expiresAt,
            long remainingSeconds,
            boolean readOnly,
            String message) {
    }
}
