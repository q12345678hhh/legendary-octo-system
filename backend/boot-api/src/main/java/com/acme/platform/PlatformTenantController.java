package com.acme.platform;

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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台运营接口：只操作租户元数据与生命周期，不提供任何租户业务正文读取端点。
 *
 * <p>平台请求禁止携带 {@code X-Tenant-Id}；要进入目标租户上下文只能由服务端按
 * 路径里的 tenantId 调用 {@code TenantTransactions}，并经过 RLS。
 */
@RestController
@RequestMapping("/platform/api/v1/tenants")
public class PlatformTenantController {

    static final String OPERATOR_HEADER = "X-Platform-Operator-Id";

    private final TenantLifecycleService lifecycleService;
    private final TenantSupportAccessService supportAccessService;

    public PlatformTenantController(TenantLifecycleService lifecycleService,
                                    TenantSupportAccessService supportAccessService) {
        this.lifecycleService = lifecycleService;
        this.supportAccessService = supportAccessService;
    }

    @GetMapping("/{tenantId}")
    public TenantLifecycleService.TenantView view(@PathVariable UUID tenantId) {
        return lifecycleService.view(tenantId);
    }

    @PostMapping
    public Object create(@RequestHeader(OPERATOR_HEADER) String operator,
                         @RequestBody CreateTenantRequest request) {
        return lifecycleService.create(new TenantLifecycleService.CreateTenantCommand(
                request.tenantId(), request.slug(), request.name(), request.planCode(),
                request.brandName(), request.brandColor(), request.locale(), request.timezone(),
                request.primaryDomain(), request.domains(), request.features()), operator);
    }

    @PostMapping("/{tenantId}/initialize")
    public Object initialize(@RequestHeader(OPERATOR_HEADER) String operator, @PathVariable UUID tenantId) {
        return lifecycleService.initialize(tenantId, operator);
    }

    @PostMapping("/{tenantId}/freeze")
    public Object freeze(@RequestHeader(OPERATOR_HEADER) String operator,
                         @PathVariable UUID tenantId,
                         @RequestBody ReasonRequest request) {
        return lifecycleService.freeze(tenantId, request.reason(), operator);
    }

    @PostMapping("/{tenantId}/restore")
    public Object restore(@RequestHeader(OPERATOR_HEADER) String operator,
                          @PathVariable UUID tenantId,
                          @RequestBody ReasonRequest request) {
        return lifecycleService.restore(tenantId, request.reason(), operator);
    }

    @PostMapping("/{tenantId}/closing")
    public Object requestClosing(@RequestHeader(OPERATOR_HEADER) String operator,
                                 @PathVariable UUID tenantId,
                                 @RequestBody ReasonRequest request) {
        return lifecycleService.requestClosing(tenantId, request.reason(), operator);
    }

    @PostMapping("/{tenantId}/closing/cancel")
    public Object cancelClosing(@RequestHeader(OPERATOR_HEADER) String operator,
                                @PathVariable UUID tenantId,
                                @RequestBody ReasonRequest request) {
        return lifecycleService.cancelClosing(tenantId, request.reason(), operator);
    }

    @DeleteMapping("/{tenantId}")
    public Object delete(@RequestHeader(OPERATOR_HEADER) String operator,
                         @PathVariable UUID tenantId,
                         @RequestBody ReasonRequest request) {
        return lifecycleService.delete(tenantId, request.reason(), operator);
    }

    @PutMapping("/{tenantId}/profile")
    public Object updateProfile(@RequestHeader(OPERATOR_HEADER) String operator,
                                @PathVariable UUID tenantId,
                                @RequestBody ProfileRequest request) {
        return lifecycleService.updateProfile(tenantId, new TenantLifecycleService.UpdateProfileCommand(
                request.brandName(), request.brandColor(), request.locale(), request.timezone(),
                request.features()), operator);
    }

    @GetMapping("/{tenantId}/quotas")
    public List<TenantQuota> quotas(@PathVariable UUID tenantId) {
        return lifecycleService.view(tenantId).quotas();
    }

    @GetMapping("/{tenantId}/audit")
    public List<?> audit(@PathVariable UUID tenantId) {
        return lifecycleService.view(tenantId).audit();
    }

    @GetMapping("/{tenantId}/support-access")
    public List<TenantSupportAccess> supportAccess(@PathVariable UUID tenantId) {
        return supportAccessService.listAll(tenantId);
    }

    @PostMapping("/{tenantId}/support-access")
    public Object requestSupportAccess(@RequestHeader(OPERATOR_HEADER) String operator,
                                       @PathVariable UUID tenantId,
                                       @RequestBody SupportAccessRequest request) {
        return supportAccessService.request(tenantId, operator, request.ticketId(),
                request.reason(), request.minutes());
    }

    @PostMapping("/{tenantId}/support-access/{accessId}/approve")
    public Object approveSupportAccess(@RequestHeader(OPERATOR_HEADER) String operator,
                                       @PathVariable UUID tenantId,
                                       @PathVariable UUID accessId) {
        return supportAccessService.approve(tenantId, accessId, operator);
    }

    @PostMapping("/{tenantId}/support-access/{accessId}/deny")
    public Object denySupportAccess(@RequestHeader(OPERATOR_HEADER) String operator,
                                    @PathVariable UUID tenantId,
                                    @PathVariable UUID accessId) {
        return supportAccessService.deny(tenantId, accessId, operator);
    }

    @PostMapping("/{tenantId}/support-access/{accessId}/revoke")
    public Object revokeSupportAccess(@RequestHeader(OPERATOR_HEADER) String operator,
                                      @PathVariable UUID tenantId,
                                      @PathVariable UUID accessId) {
        return supportAccessService.revoke(tenantId, accessId, operator);
    }

    /** 进入支持访问：返回只读标记、到期时间与必须常驻展示的 banner。 */
    @PostMapping("/{tenantId}/support-access/{accessId}/enter")
    public TenantSupportAccessService.SupportGrant enterSupportAccess(@PathVariable UUID tenantId,
                                                                      @PathVariable UUID accessId) {
        return supportAccessService.enter(tenantId, accessId);
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
