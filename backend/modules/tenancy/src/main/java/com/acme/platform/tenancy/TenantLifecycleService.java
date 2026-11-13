package com.acme.platform.tenancy;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 租户生命周期应用服务。
 *
 * <p>每个状态变更都在同一个租户事务里完成「读状态 → 条件更新 → 写审计」：
 * 条件更新保证并发下只有一个请求成功，审计保证冻结、恢复与删除可复核。
 */
@Service
public class TenantLifecycleService {

    private static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9-]{2,31}$");
    private static final Pattern DOMAIN = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");
    private static final Pattern FEATURE_KEY = Pattern.compile("^[a-z][a-z0-9_.-]{0,63}$");
    private static final int MAX_AUDIT = 100;
    private static final int MAX_APPEALS = 50;

    private final TenantTransactions tenantTransactions;
    private final TenantStore tenantStore;
    private final SupportAccessStore supportAccessStore;
    private final Clock clock;
    private final Duration coolingOff;

    public TenantLifecycleService(TenantTransactions tenantTransactions,
                                  TenantStore tenantStore,
                                  SupportAccessStore supportAccessStore,
                                  Clock clock,
                                  @Value("${platform.tenancy.cooling-off-days:30}") int coolingOffDays) {
        if (coolingOffDays < 1) {
            throw new IllegalArgumentException("platform.tenancy.cooling-off-days 必须大于 0");
        }
        this.tenantTransactions = tenantTransactions;
        this.tenantStore = tenantStore;
        this.supportAccessStore = supportAccessStore;
        this.clock = clock;
        this.coolingOff = Duration.ofDays(coolingOffDays);
    }

    public TenantRecord create(CreateTenantCommand command, String actor) {
        String creator = requireActor(actor);
        validateCreate(command);
        int maxTenants = tenantStore.maxTenants();
        if (tenantStore.countDirectory() >= maxTenants) {
            throw new ApiException(429, "TENANT_LIMIT_REACHED",
                    "平台租户数已达到上限 " + maxTenants + "，请先在平台设置中调整 limits.max_tenants");
        }
        UUID tenantId = command.tenantId() == null ? UUID.randomUUID() : command.tenantId();
        RequestContext context = context(tenantId, creator);

        try {
            return tenantTransactions.inTenant(context, ctx -> {
                TenantPlan plan = tenantStore.findPlan(command.planCode()).orElseThrow(() ->
                        ApiException.unprocessable("PLAN_UNKNOWN", "套餐不存在：" + command.planCode()));
                tenantStore.insertTenant(tenantId, command.slug(), command.name(), plan.code());
                Map<String, Boolean> features = command.features() == null || command.features().isEmpty()
                        ? plan.features() : command.features();
                validateFeatures(features);
                tenantStore.insertProfile(tenantId,
                        command.brandName() == null ? command.name() : command.brandName(),
                        command.brandColor() == null ? "#1f6feb" : command.brandColor(),
                        command.locale(), command.timezone(), features, creator);
                tenantStore.insertDomain(tenantId, command.primaryDomain(), "primary");
                for (String alias : command.domains() == null ? List.<String>of() : command.domains()) {
                    tenantStore.insertDomain(tenantId, alias, "alias");
                }
                plan.quotas().forEach((key, limit) ->
                        tenantStore.insertQuota(tenantId, key, limit, unitOf(key), periodOf(key)));
                tenantStore.insertAudit(audit(ctx, "created", null, TenantStatus.PROVISIONING,
                        creator, null, null));
                return tenantStore.find(tenantId).orElseThrow();
            });
        } catch (DataIntegrityViolationException conflict) {
            throw ApiException.conflict("TENANT_CONFLICT", "租户短标识或域名已被占用，请更换后重试");
        }
    }

    public TenantRecord initialize(UUID tenantId, String actor) {
        String operator = requireActor(actor);
        return tenantTransactions.inTenant(context(tenantId, operator), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            requireTransition(tenant, TenantStatus.ACTIVE);
            if (!tenantStore.markInitialized(tenantId)) {
                throw stateConflict(tenant, TenantStatus.ACTIVE);
            }
            tenantStore.insertAudit(audit(ctx, "initialized", tenant.status(), TenantStatus.ACTIVE,
                    operator, null, null));
            return requireTenant(tenantId);
        });
    }

    public TenantRecord freeze(UUID tenantId, String reason, String actor) {
        String operator = requireActor(actor);
        String frozenReason = requireReason(reason, "冻结原因");
        return tenantTransactions.inTenant(context(tenantId, operator), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            requireTransition(tenant, TenantStatus.FROZEN);
            if (!tenantStore.markFrozen(tenantId, frozenReason, OffsetDateTime.now(clock))) {
                throw stateConflict(tenant, TenantStatus.FROZEN);
            }
            tenantStore.insertAudit(audit(ctx, "frozen", tenant.status(), TenantStatus.FROZEN,
                    operator, frozenReason, null));
            return requireTenant(tenantId);
        });
    }

    public TenantRecord restore(UUID tenantId, String reason, String actor) {
        String operator = requireActor(actor);
        String restoreReason = requireReason(reason, "恢复原因");
        return tenantTransactions.inTenant(context(tenantId, operator), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            requireTransition(tenant, TenantStatus.ACTIVE);
            if (!tenantStore.markRestored(tenantId)) {
                throw stateConflict(tenant, TenantStatus.ACTIVE);
            }
            tenantStore.insertAudit(audit(ctx, "restored", tenant.status(), TenantStatus.ACTIVE,
                    operator, restoreReason, null));
            return requireTenant(tenantId);
        });
    }

    public TenantRecord requestClosing(UUID tenantId, String reason, String actor) {
        String operator = requireActor(actor);
        String closingReason = requireReason(reason, "注销原因");
        return tenantTransactions.inTenant(context(tenantId, operator), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            requireTransition(tenant, TenantStatus.CLOSING);
            OffsetDateTime now = OffsetDateTime.now(clock);
            OffsetDateTime coolingOffUntil = now.plus(coolingOff);
            if (!tenantStore.markClosing(tenantId, now, coolingOffUntil)) {
                throw stateConflict(tenant, TenantStatus.CLOSING);
            }
            tenantStore.insertAudit(audit(ctx, "closing_requested", tenant.status(), TenantStatus.CLOSING,
                    operator, closingReason, null));
            return requireTenant(tenantId);
        });
    }

    public TenantRecord cancelClosing(UUID tenantId, String reason, String actor) {
        String operator = requireActor(actor);
        String cancelReason = requireReason(reason, "撤回注销原因");
        return tenantTransactions.inTenant(context(tenantId, operator), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            requireTransition(tenant, TenantStatus.ACTIVE);
            if (!tenantStore.markClosingCancelled(tenantId)) {
                throw stateConflict(tenant, TenantStatus.ACTIVE);
            }
            tenantStore.insertAudit(audit(ctx, "closing_cancelled", tenant.status(), TenantStatus.ACTIVE,
                    operator, cancelReason, null));
            return requireTenant(tenantId);
        });
    }

    public TenantRecord delete(UUID tenantId, String reason, String actor) {
        String operator = requireActor(actor);
        String deleteReason = requireReason(reason, "最终删除原因");
        return tenantTransactions.inTenant(context(tenantId, operator), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            requireTransition(tenant, TenantStatus.DELETED);
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (tenant.coolingOffUntil() != null && now.isBefore(tenant.coolingOffUntil())) {
                throw ApiException.conflict("COOLING_OFF_ACTIVE",
                        "冷静期尚未结束，最早可在 " + tenant.coolingOffUntil() + " 之后最终删除");
            }
            if (!tenantStore.markDeleted(tenantId, now)) {
                throw stateConflict(tenant, TenantStatus.DELETED);
            }
            tenantStore.deleteDomains(tenantId);
            supportAccessStore.revokeAll(tenantId);
            tenantStore.insertAudit(audit(ctx, "deleted", tenant.status(), TenantStatus.DELETED,
                    operator, deleteReason, null));
            return requireTenant(tenantId);
        });
    }

    public TenantView view(UUID tenantId) {
        return tenantTransactions.inTenant(context(tenantId, null), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            return new TenantView(
                    tenant,
                    tenantStore.findProfile(tenantId).orElse(null),
                    tenantStore.findDomains(tenantId),
                    tenantStore.findQuotas(tenantId),
                    tenantStore.findAudit(tenantId, 50),
                    tenantStore.findAppeals(tenantId, 20));
        });
    }

    public TenantProfile updateProfile(UUID tenantId, UpdateProfileCommand command, String actor) {
        String operator = requireActor(actor);
        validateProfile(command);
        validateFeatures(command.features());
        return tenantTransactions.inTenant(context(tenantId, operator), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            if (tenant.status() == TenantStatus.CLOSING || tenant.status() == TenantStatus.DELETED) {
                throw new ApiException(423, "TENANT_CONFIG_LOCKED",
                        "租户处于 " + tenant.status().wire() + "，配置已锁定；如需修改请先撤回注销");
            }
            tenantStore.updateProfile(tenantId, command.brandName(), command.brandColor(),
                    command.locale(), command.timezone(), command.features(), operator);
            return tenantStore.findProfile(tenantId).orElseThrow();
        });
    }

    public List<TenantQuota> consumeQuota(UUID tenantId, String quotaKey, long amount, String actor) {
        String operator = requireActor(actor);
        if (amount <= 0) {
            throw ApiException.badRequest("QUOTA_AMOUNT_INVALID", "消耗量必须大于 0");
        }
        return tenantTransactions.inTenant(context(tenantId, operator), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            if (!tenant.status().writable()) {
                throw new ApiException(423, "TENANT_FROZEN",
                        "租户状态为 " + tenant.status().wire() + "，已阻断业务写入与配额消耗");
            }
            if (!tenantStore.consumeQuota(tenantId, quotaKey, amount)) {
                throw new ApiException(429, "QUOTA_EXCEEDED", "配额不足或配额键不存在：" + quotaKey);
            }
            return tenantStore.findQuotas(tenantId);
        });
    }

    public BusinessNumber issueBusinessNumber(UUID tenantId, String scope, String actor) {
        String operator = requireActor(actor);
        if (scope == null || !scope.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw ApiException.badRequest("BUSINESS_SCOPE_INVALID",
                    "业务域必须是大写字母开头、可含数字与下划线，长度 2–32");
        }
        return tenantTransactions.inTenant(context(tenantId, operator), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            if (!tenant.status().writable()) {
                throw new ApiException(423, "TENANT_FROZEN",
                        "租户状态为 " + tenant.status().wire() + "，已阻断业务写入与编号发放");
            }
            long sequence = tenantStore.nextBusinessSequence(tenantId, scope);
            String number = scope + "-" + String.format("%06d", sequence);
            tenantStore.insertBusinessNumber(tenantId, scope, number, sequence);
            return new BusinessNumber(scope, number, sequence);
        });
    }

    public TenantExport export(UUID tenantId, String actor) {
        String operator = requireActor(actor);
        return tenantTransactions.inTenant(context(tenantId, operator), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            if (tenant.status() == TenantStatus.DELETED) {
                throw new ApiException(410, "TENANT_DELETED", "租户已最终删除，导出通道已关闭");
            }
            TenantExport export = new TenantExport(
                    OffsetDateTime.now(clock),
                    tenant,
                    tenantStore.findProfile(tenantId).orElse(null),
                    tenantStore.findDomains(tenantId),
                    tenantStore.findQuotas(tenantId),
                    tenantStore.findAudit(tenantId, MAX_AUDIT),
                    tenantStore.findAppeals(tenantId, MAX_APPEALS),
                    "受控导出：仅包含租户配置、配额、生命周期审计与申诉，不含业务正文");
            tenantStore.insertAudit(audit(ctx, "exported", tenant.status(), tenant.status(),
                    operator, "受控导出", null));
            return export;
        });
    }

    public TenantAppeal submitAppeal(UUID tenantId, String subjectId, String reason) {
        String subject = requireActor(subjectId);
        String appealReason = requireReason(reason, "申诉理由");
        if (appealReason.length() > 2000) {
            throw ApiException.badRequest("APPEAL_REASON_INVALID", "申诉理由不能超过 2000 个字符");
        }
        return tenantTransactions.inTenant(context(tenantId, subject), ctx -> {
            TenantRecord tenant = requireTenant(tenantId);
            if (tenant.status() != TenantStatus.FROZEN && tenant.status() != TenantStatus.CLOSING) {
                throw ApiException.conflict("APPEAL_NOT_AVAILABLE",
                        "只有冻结或注销冷静期内可以提交申诉，当前状态为 " + tenant.status().wire());
            }
            UUID id = UUID.randomUUID();
            tenantStore.insertAppeal(id, tenantId, subject, appealReason);
            tenantStore.insertAudit(audit(ctx, "appeal_submitted", tenant.status(), tenant.status(),
                    subject, appealReason, null));
            return new TenantAppeal(id, tenantId, subject, appealReason, "submitted",
                    OffsetDateTime.now(clock));
        });
    }

    private TenantRecord requireTenant(UUID tenantId) {
        return tenantStore.find(tenantId).orElseThrow(() -> ApiException.notFound(
                "TENANT_NOT_FOUND", "租户不存在或当前上下文无权访问"));
    }

    private void requireTransition(TenantRecord tenant, TenantStatus target) {
        if (!tenant.status().canTransitionTo(target)) {
            throw ApiException.conflict("TENANT_STATE_INVALID",
                    "租户状态 " + tenant.status().wire() + " 不能迁移到 " + target.wire());
        }
    }

    private ApiException stateConflict(TenantRecord tenant, TenantStatus target) {
        return ApiException.conflict("TENANT_STATE_CONFLICT",
                "租户状态已被并发修改（读取时 " + tenant.status().wire() + "，目标 " + target.wire() + "），请刷新后重试");
    }

    private void validateCreate(CreateTenantCommand command) {
        if (command == null) {
            throw ApiException.badRequest("TENANT_COMMAND_REQUIRED", "缺少租户创建参数");
        }
        if (command.slug() == null || !SLUG.matcher(command.slug()).matches()) {
            throw ApiException.badRequest("TENANT_SLUG_INVALID",
                    "租户短标识必须是小写字母开头、可含数字与连字符，长度 3–32");
        }
        if (command.name() == null || command.name().trim().isEmpty() || command.name().trim().length() > 100) {
            throw ApiException.badRequest("TENANT_NAME_INVALID", "租户名称长度必须在 1–100 个字符之间");
        }
        if (command.primaryDomain() == null || !DOMAIN.matcher(command.primaryDomain()).matches()) {
            throw ApiException.badRequest("TENANT_DOMAIN_INVALID", "主域名必须是合法的小写 FQDN");
        }
        for (String alias : command.domains() == null ? List.<String>of() : command.domains()) {
            if (alias == null || !DOMAIN.matcher(alias).matches() || alias.equals(command.primaryDomain())) {
                throw ApiException.badRequest("TENANT_DOMAIN_INVALID", "别名域名必须合法且不能与主域名重复");
            }
        }
        validateProfile(new UpdateProfileCommand(
                command.brandName() == null ? command.name() : command.brandName(),
                command.brandColor() == null ? "#1f6feb" : command.brandColor(),
                command.locale(), command.timezone(), command.features()));
    }

    private void validateProfile(UpdateProfileCommand command) {
        if (command.brandName() == null || command.brandName().trim().isEmpty()
                || command.brandName().trim().length() > 100) {
            throw ApiException.badRequest("BRAND_NAME_INVALID", "品牌名称长度必须在 1–100 个字符之间");
        }
        if (command.brandColor() == null || !command.brandColor().matches("^#[0-9a-fA-F]{6}$")) {
            throw ApiException.badRequest("BRAND_COLOR_INVALID", "品牌色必须是 #RRGGBB");
        }
        if (!"zh-CN".equals(command.locale()) && !"en-US".equals(command.locale())) {
            throw ApiException.badRequest("LOCALE_INVALID", "语言只能是 zh-CN 或 en-US");
        }
        validateTimezone(command.timezone());
    }

    private void validateTimezone(String timezone) {
        if (timezone == null || timezone.trim().isEmpty() || timezone.length() > 64) {
            throw ApiException.badRequest("TIMEZONE_INVALID", "时区不能为空且长度不能超过 64");
        }
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException invalid) {
            throw ApiException.badRequest("TIMEZONE_INVALID", "未知时区：" + timezone);
        }
    }

    private void validateFeatures(Map<String, Boolean> features) {
        if (features == null) {
            return;
        }
        if (features.size() > 100) {
            throw ApiException.badRequest("FEATURES_TOO_MANY", "功能开关不能超过 100 项");
        }
        for (Map.Entry<String, Boolean> entry : features.entrySet()) {
            if (entry.getKey() == null || !FEATURE_KEY.matcher(entry.getKey()).matches()) {
                throw ApiException.badRequest("FEATURE_KEY_INVALID", "非法的功能开关键：" + entry.getKey());
            }
            if (entry.getValue() == null) {
                throw ApiException.badRequest("FEATURE_VALUE_INVALID", "功能开关只能是布尔值：" + entry.getKey());
            }
        }
    }

    private String requireActor(String actor) {
        if (actor == null || actor.trim().isEmpty()) {
            throw ApiException.badRequest("ACTOR_REQUIRED", "缺少操作人：写操作必须可追溯");
        }
        return actor.trim();
    }

    private String requireReason(String reason, String label) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < 5 || trimmed.length() > 500) {
            throw ApiException.badRequest("REASON_INVALID", label + "长度必须在 5–500 个字符之间");
        }
        return trimmed;
    }

    private TenantLifecycleAudit audit(RequestContext context, String action, TenantStatus from,
                                       TenantStatus to, String actor, String reason, String ticketId) {
        return new TenantLifecycleAudit(UUID.randomUUID(), context.tenantId(), action, from, to,
                actor, reason, ticketId, context.correlationId(), context.traceId(),
                OffsetDateTime.now(clock));
    }

    private RequestContext context(UUID tenantId, String actor) {
        RequestContext current = RequestContextHolder.find().orElse(null);
        return new RequestContext(tenantId, actor,
                current == null ? UUID.randomUUID().toString() : current.correlationId(),
                current == null ? traceId() : current.traceId());
    }

    private String traceId() {
        return RequestContextHolder.find().map(RequestContext::traceId).orElseGet(TenantLifecycleService::newTraceId);
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String unitOf(String quotaKey) {
        return switch (quotaKey) {
            case "storage_mb" -> "MB";
            case "ai_tokens" -> "tokens";
            default -> "count";
        };
    }

    private static String periodOf(String quotaKey) {
        return "ai_tokens".equals(quotaKey) ? "month" : "total";
    }

    /** 创建租户参数；{@code tenantId} 仅用于测试固定标识，生产由调用方留空自动生成。 */
    public record CreateTenantCommand(
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

        public CreateTenantCommand {
            planCode = planCode == null ? "trial" : planCode;
            locale = locale == null ? "zh-CN" : locale;
            timezone = timezone == null ? "Asia/Shanghai" : timezone;
            domains = domains == null ? List.of() : List.copyOf(domains);
            features = features == null ? Map.of() : new LinkedHashMap<>(features);
        }
    }

    public record UpdateProfileCommand(
            String brandName,
            String brandColor,
            String locale,
            String timezone,
            Map<String, Boolean> features) {

        public UpdateProfileCommand {
            features = features == null ? Map.of() : new LinkedHashMap<>(features);
        }
    }

    public record TenantView(
            TenantRecord tenant,
            TenantProfile profile,
            List<TenantDomain> domains,
            List<TenantQuota> quotas,
            List<TenantLifecycleAudit> audit,
            List<TenantAppeal> appeals) {
    }

    public record TenantExport(
            OffsetDateTime generatedAt,
            TenantRecord tenant,
            TenantProfile profile,
            List<TenantDomain> domains,
            List<TenantQuota> quotas,
            List<TenantLifecycleAudit> audit,
            List<TenantAppeal> appeals,
            String notice) {
    }

    public record BusinessNumber(String scope, String number, long sequence) {
    }
}
