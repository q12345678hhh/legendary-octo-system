package com.acme.platform;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.tenancy.TenantLifecycleService;
import com.acme.platform.tenancy.TenantQuota;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户自助接口：查看自身生命周期、配置、配额，发起注销/撤回、申诉与受控导出。
 *
 * <p>所有请求都必须带 {@code X-Tenant-Id}；平台运营标识在这里一律拒绝，由
 * {@link PlatformDomainIsolationFilter} 统一拦截。
 */
@RestController
@RequestMapping("/api/v1/tenant")
public class TenantController {

    private final TenantLifecycleService lifecycleService;

    public TenantController(TenantLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    public TenantLifecycleService.TenantView view() {
        return lifecycleService.view(currentTenantId());
    }

    @PutMapping("/profile")
    public Object updateProfile(@RequestBody ProfileRequest request) {
        return lifecycleService.updateProfile(currentTenantId(), new TenantLifecycleService.UpdateProfileCommand(
                request.brandName(), request.brandColor(), request.locale(), request.timezone(),
                request.features()), actor());
    }

    @PostMapping("/closing")
    public Object requestClosing(@RequestBody ReasonRequest request) {
        return lifecycleService.requestClosing(currentTenantId(), request.reason(), actor());
    }

    @PostMapping("/closing/cancel")
    public Object cancelClosing(@RequestBody ReasonRequest request) {
        return lifecycleService.cancelClosing(currentTenantId(), request.reason(), actor());
    }

    /** 受控导出：冻结/注销冷静期仍可用；响应带明确标记且禁止缓存。 */
    @GetMapping("/export")
    public ResponseEntity<TenantLifecycleService.TenantExport> export() {
        TenantLifecycleService.TenantExport export = lifecycleService.export(currentTenantId(), actor());
        return ResponseEntity.ok()
                .header("X-Controlled-Export", "tenant-lifecycle")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(export);
    }

    /** 冻结或注销冷静期内仍可提交申诉。 */
    @PostMapping("/appeals")
    public Object submitAppeal(@RequestBody AppealRequest request) {
        return lifecycleService.submitAppeal(currentTenantId(), actor(), request.reason());
    }

    @GetMapping("/audit")
    public List<?> audit() {
        return lifecycleService.view(currentTenantId()).audit();
    }

    @PostMapping("/quotas/{quotaKey}/consume")
    public List<TenantQuota> consumeQuota(@PathVariable String quotaKey,
                                          @RequestBody ConsumeQuotaRequest request) {
        return lifecycleService.consumeQuota(currentTenantId(), quotaKey, request.amount(), actor());
    }

    @PostMapping("/business-numbers/{scope}")
    public TenantLifecycleService.BusinessNumber issueBusinessNumber(@PathVariable String scope) {
        return lifecycleService.issueBusinessNumber(currentTenantId(), scope, actor());
    }

    private UUID currentTenantId() {
        RequestContext context = RequestContextHolder.find().orElseThrow(() ->
                ApiException.badRequest("TENANT_REQUIRED", "缺少请求上下文"));
        if (context.tenantId() == null) {
            throw ApiException.badRequest("TENANT_REQUIRED", "请求必须携带 X-Tenant-Id");
        }
        return context.tenantId();
    }

    private String actor() {
        return RequestContextHolder.find().map(RequestContext::subjectId)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> ApiException.badRequest(
                        "ACTOR_REQUIRED", "写操作必须已认证，便于审计追溯"));
    }

    public record ProfileRequest(String brandName, String brandColor, String locale,
                                 String timezone, java.util.Map<String, Boolean> features) {
    }

    public record ReasonRequest(String reason) {
    }

    public record AppealRequest(String reason) {
    }

    public record ConsumeQuotaRequest(long amount) {
    }
}
