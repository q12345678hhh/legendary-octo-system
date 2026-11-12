package com.acme.platform;

import com.acme.platform.identity.operator.PlatformActorContext;
import com.acme.platform.identity.operator.PlatformPermissionCatalog;
import com.acme.platform.platform.audit.PlatformAuditStore;
import com.acme.platform.platform.directory.PlatformDirectoryStore;
import com.acme.platform.tenancy.TenantLifecycleService;
import com.acme.platform.tenancy.TenantQuota;
import com.acme.platform.tenancy.TenantSupportAccess;
import com.acme.platform.tenancy.TenantSupportAccessService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台运营接口：只操作租户元数据与生命周期，不提供任何租户业务正文读取端点。
 *
 * <p>平台请求禁止携带 {@code X-Tenant-Id}；要进入目标租户上下文只能由服务端按
 * 路径里的 tenantId 调用 {@code TenantTransactions}，并经过 RLS。写操作同时写
 * {@code platform_audit_event}，与租户生命周期审计互相印证。
 */
@RestController
@RequestMapping("/platform/api/v1/tenants")
public class PlatformTenantController {

    private final TenantLifecycleService lifecycleService;
    private final TenantSupportAccessService supportAccessService;
    private final PlatformDirectoryStore directoryStore;
    private final PlatformAuditStore auditStore;

    public PlatformTenantController(TenantLifecycleService lifecycleService,
                                    TenantSupportAccessService supportAccessService,
                                    PlatformDirectoryStore directoryStore,
                                    PlatformAuditStore auditStore) {
        this.lifecycleService = lifecycleService;
        this.supportAccessService = supportAccessService;
        this.directoryStore = directoryStore;
        this.auditStore = auditStore;
    }

    @GetMapping
    List<PlatformDirectoryStore.TenantSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit) {
        PlatformActors.require(PlatformPermissionCatalog.TENANT_READ);
        return directoryStore.list(status, q, limit);
    }

    @GetMapping("/{tenantId}")
    public TenantLifecycleService.TenantView view(@PathVariable UUID tenantId) {
        PlatformActors.require(PlatformPermissionCatalog.TENANT_READ);
        return lifecycleService.view(tenantId);
    }

    @PostMapping
    public Object create(@RequestBody CreateTenantRequest request) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.TENANT_MANAGE);
        Object created = lifecycleService.create(new TenantLifecycleService.CreateTenantCommand(
                request.tenantId(), request.slug(), request.name(), request.planCode(),
                request.brandName(), request.brandColor(), request.locale(), request.timezone(),
                request.primaryDomain(), request.domains(), request.features()), actor.email());
        audit(actor, "tenant.create", String.valueOf(request.tenantId()));
        return created;
    }

    @PostMapping("/{tenantId}/initialize")
    public Object initialize(@PathVariable UUID tenantId) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.TENANT_MANAGE);
        Object result = lifecycleService.initialize(tenantId, actor.email());
        audit(actor, "tenant.initialize", tenantId.toString());
        return result;
    }

    @PostMapping("/{tenantId}/freeze")
    public Object freeze(@PathVariable UUID tenantId, @RequestBody ReasonRequest request) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.TENANT_MANAGE);
        Object result = lifecycleService.freeze(tenantId, request.reason(), actor.email());
        audit(actor, "tenant.freeze", tenantId.toString());
        return result;
    }

    @PostMapping("/{tenantId}/restore")
    public Object restore(@PathVariable UUID tenantId, @RequestBody ReasonRequest request) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.TENANT_MANAGE);
        Object result = lifecycleService.restore(tenantId, request.reason(), actor.email());
        audit(actor, "tenant.restore", tenantId.toString());
        return result;
    }

    @PostMapping("/{tenantId}/closing")
    public Object requestClosing(@PathVariable UUID tenantId, @RequestBody ReasonRequest request) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.TENANT_MANAGE);
        Object result = lifecycleService.requestClosing(tenantId, request.reason(), actor.email());
        audit(actor, "tenant.closing.request", tenantId.toString());
        return result;
    }

    @PostMapping("/{tenantId}/closing/cancel")
    public Object cancelClosing(@PathVariable UUID tenantId, @RequestBody ReasonRequest request) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.TENANT_MANAGE);
        Object result = lifecycleService.cancelClosing(tenantId, request.reason(), actor.email());
        audit(actor, "tenant.closing.cancel", tenantId.toString());
        return result;
    }

    @DeleteMapping("/{tenantId}")
    public Object delete(@PathVariable UUID tenantId, @RequestBody ReasonRequest request) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.TENANT_MANAGE);
        Object result = lifecycleService.delete(tenantId, request.reason(), actor.email());
        audit(actor, "tenant.delete", tenantId.toString());
        return result;
    }

    @PutMapping("/{tenantId}/profile")
    public Object updateProfile(@PathVariable UUID tenantId, @RequestBody ProfileRequest request) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.TENANT_MANAGE);
        Object result = lifecycleService.updateProfile(tenantId, new TenantLifecycleService.UpdateProfileCommand(
                request.brandName(), request.brandColor(), request.locale(), request.timezone(),
                request.features()), actor.email());
        audit(actor, "tenant.profile.update", tenantId.toString());
        return result;
    }

    @GetMapping("/{tenantId}/quotas")
    public List<TenantQuota> quotas(@PathVariable UUID tenantId) {
        PlatformActors.require(PlatformPermissionCatalog.TENANT_READ);
        return lifecycleService.view(tenantId).quotas();
    }

    @GetMapping("/{tenantId}/audit")
    public List<?> audit(@PathVariable UUID tenantId) {
        PlatformActors.require(PlatformPermissionCatalog.TENANT_READ);
        return lifecycleService.view(tenantId).audit();
    }

    @GetMapping("/{tenantId}/support-access")
    public List<TenantSupportAccess> supportAccess(@PathVariable UUID tenantId) {
        PlatformActors.require(PlatformPermissionCatalog.TENANT_READ);
        return supportAccessService.listAll(tenantId);
    }

    @PostMapping("/{tenantId}/support-access")
    public Object requestSupportAccess(@PathVariable UUID tenantId,
                                       @RequestBody SupportAccessRequest request) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.SUPPORT_MANAGE);
        Object result = supportAccessService.request(tenantId, actor.email(), request.ticketId(),
                request.reason(), request.minutes());
        audit(actor, "support_access.request", tenantId.toString());
        return result;
    }

    @PostMapping("/{tenantId}/support-access/{accessId}/approve")
    public Object approveSupportAccess(@PathVariable UUID tenantId,
                                       @PathVariable UUID accessId) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.SUPPORT_MANAGE);
        Object result = supportAccessService.approve(tenantId, accessId, actor.email());
        audit(actor, "support_access.approve", accessId.toString());
        return result;
    }

    @PostMapping("/{tenantId}/support-access/{accessId}/deny")
    public Object denySupportAccess(@PathVariable UUID tenantId, @PathVariable UUID accessId) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.SUPPORT_MANAGE);
        Object result = supportAccessService.deny(tenantId, accessId, actor.email());
        audit(actor, "support_access.deny", accessId.toString());
        return result;
    }

    @PostMapping("/{tenantId}/support-access/{accessId}/revoke")
    public Object revokeSupportAccess(@PathVariable UUID tenantId, @PathVariable UUID accessId) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.SUPPORT_MANAGE);
        Object result = supportAccessService.revoke(tenantId, accessId, actor.email());
        audit(actor, "support_access.revoke", accessId.toString());
        return result;
    }

    /** 进入支持访问：返回只读标记、到期时间与必须常驻展示的 banner。 */
    @PostMapping("/{tenantId}/support-access/{accessId}/enter")
    public TenantSupportAccessService.SupportGrant enterSupportAccess(
            @PathVariable UUID tenantId, @PathVariable UUID accessId) {
        PlatformActorContext actor = PlatformActors.requireManage(
                PlatformPermissionCatalog.SUPPORT_MANAGE);
        TenantSupportAccessService.SupportGrant grant = supportAccessService.enter(tenantId, accessId);
        auditStore.record(PlatformActors.ref(actor), "support_access.enter",
                "tenancy_support_access", accessId.toString(),
                Map.of("ticketId", grant.ticketId(), "readOnly", grant.readOnly()));
        return grant;
    }

    private void audit(PlatformActorContext actor, String action, String targetId) {
        auditStore.record(PlatformActors.ref(actor), action, "tenancy_tenant", targetId, Map.of());
    }

    public record CreateTenantRequest(
            UUID tenantId,
            String slug,
            String name,
            String planCode,
            String brandName,
            String brandColor,
            String locale,
            String timezone,
            String primaryDomain,
            List<String> domains,
            Map<String, Boolean> features) {
    }

    public record ReasonRequest(String reason) {
    }

    public record ProfileRequest(String brandName, String brandColor, String locale,
                                 String timezone, Map<String, Boolean> features) {
    }

    public record SupportAccessRequest(String ticketId, String reason, int minutes) {
    }
}
