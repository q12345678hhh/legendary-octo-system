package com.acme.platform.tenancy;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 平台支持访问：申请 → 独立审批 → 限时只读 → 进入时展示醒目标识。
 *
 * <p>平台运营默认没有租户业务正文的读取通道；支持访问是唯一例外，且必须同时满足
 * 工单、理由、独立审批人、最长 60 分钟与全量留痕。
 */
@Service
public class TenantSupportAccessService {

    private final TenantTransactions tenantTransactions;
    private final TenantStore tenantStore;
    private final SupportAccessStore supportAccessStore;
    private final Clock clock;

    public TenantSupportAccessService(TenantTransactions tenantTransactions,
                                      TenantStore tenantStore,
                                      SupportAccessStore supportAccessStore,
                                      Clock clock) {
        this.tenantTransactions = tenantTransactions;
        this.tenantStore = tenantStore;
        this.supportAccessStore = supportAccessStore;
        this.clock = clock;
    }

    public TenantSupportAccess request(UUID tenantId, String operatorId, String ticketId,
                                       String reason, int minutes) {
        String operator = requireOperator(operatorId);
        SupportAccessRules.validateRequest(ticketId, reason, minutes);
        String trimmedReason = reason.trim();
        return tenantTransactions.inTenant(context(tenantId, operator), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            if (tenant.status() == TenantStatus.DELETED) {
                throw new ApiException(410, "TENANT_DELETED", "租户已最终删除，不能申请支持访问");
            }
            TenantSupportAccess access = supportAccessStore.insert(
                    UUID.randomUUID(), tenantId, ticketId, operator, trimmedReason, minutes, "tenant_admin_read");
            tenantStore.insertAudit(audit(ctx, "support_access_requested", tenant,
                    operator, trimmedReason, ticketId));
            return access;
        });
    }

    public TenantSupportAccess approve(UUID tenantId, UUID accessId, String approverId) {
        String approver = requireOperator(approverId);
        return tenantTransactions.inTenant(context(tenantId, approver), ctx -> {
            TenantSupportAccess access = requireAccess(tenantId, accessId);
            SupportAccessRules.validateApprover(access.operatorId(), approver);
            if (access.status() != SupportAccessStatus.PENDING) {
                throw ApiException.conflict("SUPPORT_ACCESS_NOT_PENDING",
                        "只有待审批的支持访问可以批准，当前状态为 " + access.status().wire());
            }
            OffsetDateTime approvedAt = OffsetDateTime.now(clock);
            OffsetDateTime expiresAt = approvedAt.plusMinutes(access.requestedMinutes());
            if (!supportAccessStore.approve(tenantId, accessId, approver, approvedAt, expiresAt)) {
                throw ApiException.conflict("SUPPORT_ACCESS_CONFLICT", "支持访问已被并发处理，请刷新后重试");
            }
            TenantRecord tenant = requireTenant(tenantId);
            tenantStore.insertAudit(audit(ctx, "support_access_approved", tenant,
                    approver, "批准工单 " + access.ticketId(), access.ticketId()));
            return requireAccess(tenantId, accessId);
        });
    }

    public TenantSupportAccess deny(UUID tenantId, UUID accessId, String approverId) {
        String approver = requireOperator(approverId);
        return tenantTransactions.inTenant(context(tenantId, approver), ctx -> {
            TenantSupportAccess access = requireAccess(tenantId, accessId);
            SupportAccessRules.validateApprover(access.operatorId(), approver);
            if (access.status() != SupportAccessStatus.PENDING) {
                throw ApiException.conflict("SUPPORT_ACCESS_NOT_PENDING",
                        "只有待审批的支持访问可以拒绝，当前状态为 " + access.status().wire());
            }
            if (!supportAccessStore.deny(tenantId, accessId, approver)) {
                throw ApiException.conflict("SUPPORT_ACCESS_CONFLICT", "支持访问已被并发处理，请刷新后重试");
            }
            TenantRecord tenant = requireTenant(tenantId);
            tenantStore.insertAudit(audit(ctx, "support_access_denied", tenant,
                    approver, "拒绝工单 " + access.ticketId(), access.ticketId()));
            return requireAccess(tenantId, accessId);
        });
    }

    public TenantSupportAccess revoke(UUID tenantId, UUID accessId, String actor) {
        String operator = requireOperator(actor);
        return tenantTransactions.inTenant(context(tenantId, operator), ctx -> {
            TenantSupportAccess access = requireAccess(tenantId, accessId);
            if (!supportAccessStore.revoke(tenantId, accessId)) {
                throw ApiException.conflict("SUPPORT_ACCESS_NOT_REVOCABLE",
                        "支持访问当前状态为 " + access.status().wire() + "，不能撤销");
            }
            TenantRecord tenant = requireTenant(tenantId);
            tenantStore.insertAudit(audit(ctx, "support_access_revoked", tenant,
                    operator, "撤销工单 " + access.ticketId(), access.ticketId()));
            return requireAccess(tenantId, accessId);
        });
    }

    public List<TenantSupportAccess> listAll(UUID tenantId) {
        return tenantTransactions.inTenant(context(tenantId, null),
                ctx -> supportAccessStore.list(tenantId));
    }

    /** 进入支持访问：校验审批、时限与撤销状态，返回必须常驻展示的醒目标识。 */
    public SupportGrant enter(UUID tenantId, UUID accessId) {
        return tenantTransactions.inTenant(context(tenantId, null), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            if (tenant.status() == TenantStatus.DELETED) {
                throw new ApiException(410, "TENANT_DELETED", "租户已最终删除，支持访问已关闭");
            }
            TenantSupportAccess access = requireAccess(tenantId, accessId);
            if (access.status() == SupportAccessStatus.APPROVED
                    && SupportAccessRules.expired(access, OffsetDateTime.now(clock))) {
                supportAccessStore.markExpired(tenantId, accessId);
                throw ApiException.forbidden("SUPPORT_ACCESS_EXPIRED",
                        "支持访问已超过时限，请重新申请并审批");
            }
            if (access.status() != SupportAccessStatus.APPROVED) {
                throw ApiException.forbidden("SUPPORT_ACCESS_INACTIVE",
                        "支持访问未生效或已结束，当前状态为 " + access.status().wire());
            }
            supportAccessStore.touch(tenantId, accessId);
            OffsetDateTime now = OffsetDateTime.now(clock);
            return new SupportGrant(accessId, tenantId, access.ticketId(), access.operatorId(),
                    true, access.expiresAt(), access.banner(now));
        });
    }

    private TenantRecord requireTenant(UUID tenantId) {
        return tenantStore.find(tenantId).orElseThrow(() -> ApiException.notFound(
                "TENANT_NOT_FOUND", "租户不存在或当前上下文无权访问"));
    }

    private TenantSupportAccess requireAccess(UUID tenantId, UUID accessId) {
        return supportAccessStore.find(tenantId, accessId).orElseThrow(() -> ApiException.notFound(
                "SUPPORT_ACCESS_NOT_FOUND", "支持访问记录不存在"));
    }

    private String requireOperator(String operatorId) {
        if (operatorId == null || operatorId.trim().isEmpty()) {
            throw ApiException.badRequest("OPERATOR_REQUIRED", "缺少操作人");
        }
        return operatorId.trim();
    }

    private RequestContext context(UUID tenantId, String actor) {
        RequestContext current = RequestContextHolder.find().orElse(null);
        return new RequestContext(tenantId, actor,
                current == null ? UUID.randomUUID().toString() : current.correlationId(),
                current == null ? UUID.randomUUID().toString().replace("-", "") : current.traceId());
    }

    private TenantLifecycleAudit audit(RequestContext context, String action, TenantRecord tenant,
                                       String actor, String reason, String ticketId) {
        return new TenantLifecycleAudit(UUID.randomUUID(), tenant.id(), action, tenant.status(),
                tenant.status(), actor, reason, ticketId, context.correlationId(), context.traceId(),
                OffsetDateTime.now(clock));
    }

    /** 进入结果：banner 必须由前端常驻展示，不能折叠或仅放入通知中心。 */
    public record SupportGrant(
            UUID accessId,
            UUID tenantId,
            String ticketId,
            String operatorId,
            boolean readOnly,
            OffsetDateTime expiresAt,
            TenantSupportAccess.SupportAccessBanner banner) {
    }
}
