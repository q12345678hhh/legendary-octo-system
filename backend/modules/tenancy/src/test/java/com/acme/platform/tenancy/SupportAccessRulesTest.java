package com.acme.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.platform.ApiException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupportAccessRulesTest {

    @Test
    void rejectsMalformedTicketAndReasonAndDuration() {
        assertThatThrownBy(() -> SupportAccessRules.validateRequest("tck-1", "需要核对导出的审计记录", 15))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SUPPORT_TICKET_INVALID"));
        assertThatThrownBy(() -> SupportAccessRules.validateRequest("TCK-1001", "太短", 15))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SUPPORT_REASON_INVALID"));
        assertThatThrownBy(() -> SupportAccessRules.validateRequest("TCK-1001", "需要核对导出的审计记录", 61))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SUPPORT_DURATION_INVALID"));
    }

    @Test
    void approverMustDifferFromRequester() {
        assertThatThrownBy(() -> SupportAccessRules.validateApprover("ops-a", "ops-a"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(403);
                    assertThat(error.code()).isEqualTo("SUPPORT_SEPARATION_REQUIRED");
                });
        assertThatThrownBy(() -> SupportAccessRules.validateApprover("ops-a", " "))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SUPPORT_APPROVER_REQUIRED"));
        SupportAccessRules.validateApprover("ops-a", "ops-b");
    }

    @Test
    void expiryUsesApprovedExpiresAt() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-11T10:00:00Z");
        TenantSupportAccess active = access(now.plusMinutes(5), SupportAccessStatus.APPROVED, null);
        TenantSupportAccess expired = access(now.minusMinutes(1), SupportAccessStatus.APPROVED, null);
        TenantSupportAccess revoked = access(now.plusMinutes(5), SupportAccessStatus.REVOKED, now);

        assertThat(SupportAccessRules.expired(active, now)).isFalse();
        assertThat(active.activeAt(now)).isTrue();
        assertThat(SupportAccessRules.expired(expired, now)).isTrue();
        assertThat(revoked.activeAt(now)).isFalse();
    }

    private TenantSupportAccess access(OffsetDateTime expiresAt, SupportAccessStatus status,
                                       OffsetDateTime revokedAt) {
        return new TenantSupportAccess(UUID.randomUUID(), UUID.randomUUID(), "TCK-1001", "ops-a",
                "核对冻结后的导出与审计记录", 15, "tenant_admin_read", status,
                expiresAt.minusMinutes(15), "ops-b", expiresAt.minusMinutes(15), expiresAt,
                revokedAt, null);
    }
}
