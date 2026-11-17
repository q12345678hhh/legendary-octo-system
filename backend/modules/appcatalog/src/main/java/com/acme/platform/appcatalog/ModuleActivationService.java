package com.acme.platform.appcatalog;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 模块启停的应用服务。
 *
 * <p>语义：记录缺失视为启用；只有显式 disabled 才在运行时拦截。
 * 启用要求 install_status=installed；重复设置同一状态幂等；版本不一致返回 409。
 * 每次实际状态变化都追加只追加事件，并立即失效本机租户缓存。
 */
@Service
public class ModuleActivationService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final ModuleActivationStore activationStore;
    private final AppCatalogStore catalogStore;
    private final TenantTransactions transactions;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    public ModuleActivationService(ModuleActivationStore activationStore,
                                   AppCatalogStore catalogStore,
                                   TenantTransactions transactions) {
        this.activationStore = activationStore;
        this.catalogStore = catalogStore;
        this.transactions = transactions;
    }

    public record ActivationView(UUID moduleId, String code, String status, long version,
                                 String config, OffsetDateTime enabledAt, String enabledBy,
                                 OffsetDateTime disabledAt, String disabledBy,
                                 OffsetDateTime updatedAt, boolean explicit) {
    }

    private record CacheEntry(Set<String> codes, Instant expiresAt) {
    }

    public ActivationView get(UUID moduleId) {
        return transactions.inTenant(context -> {
            AppCatalogStore.ModuleRow module = requireModule(moduleId);
            return view(module, activationStore.find(moduleId));
        });
    }

    public ActivationView set(UUID moduleId, String status, Long expectedVersion, String comment,
                              String actor) {
        String target = status == null ? "" : status.trim();
        if (!"enabled".equals(target) && !"disabled".equals(target)) {
            throw ApiException.badRequest("MODULE_ACTIVATION_STATUS_INVALID",
                    "启用状态只能是 enabled 或 disabled");
        }
        String operator = actor == null ? "" : actor;
        return transactions.inTenant(context -> {
            AppCatalogStore.ModuleRow module = requireModule(moduleId);
            Optional<ModuleActivationStore.ActivationRow> existing = activationStore.find(moduleId);
            String currentStatus = existing.map(ModuleActivationStore.ActivationRow::status)
                    .orElse("enabled");
            if (target.equals(currentStatus)) {
                return view(module, existing);
            }
            if ("enabled".equals(target) && !"installed".equals(module.installStatus())) {
                throw ApiException.conflict("MODULE_NOT_INSTALLED",
                        "模块尚未安装，不能启用；请先在模块目录中标记已安装");
            }
            long currentVersion = existing.map(ModuleActivationStore.ActivationRow::version)
                    .orElse(0L);
            if (expectedVersion != null && expectedVersion != currentVersion) {
                throw ApiException.conflict("MODULE_ACTIVATION_VERSION_CONFLICT",
                        "模块启用状态已被其他人修改，请刷新后重试");
            }
            if (existing.isPresent()) {
                if (!activationStore.updateStatus(moduleId, target, currentVersion, operator,
                        comment == null ? "" : comment.trim())) {
                    throw ApiException.conflict("MODULE_ACTIVATION_VERSION_CONFLICT",
                            "模块启用状态已被其他人修改，请刷新后重试");
                }
            } else {
                activationStore.insert(moduleId, target, operator,
                        comment == null ? "" : comment.trim());
            }
            invalidate(context.tenantId());
            return view(module, activationStore.find(moduleId));
        });
    }

    /** 当前租户实际启用的模块 code：全部目录模块减去显式停用。 */
    public Set<String> enabledModuleCodes() {
        RequestContext context = RequestContextHolder.find().orElse(null);
        if (context == null || context.tenantId() == null) {
            // 没有租户上下文时不能误拦截；调用方（过滤器）在认证之后才调用本方法。
            return Set.copyOf(ModuleRegistry.codes());
        }
        UUID tenantId = context.tenantId();
        CacheEntry entry = cache.get(tenantId);
        Instant now = Instant.now();
        if (entry != null && entry.expiresAt().isAfter(now)) {
            return entry.codes();
        }
        Set<String> codes = transactions.inTenant(ctx -> computeEnabled());
        cache.put(tenantId, new CacheEntry(codes, now.plus(CACHE_TTL)));
        return codes;
    }

    /** 供目录服务判断：受管租户新增模块默认停用。 */
    public boolean tenantManaged() {
        return transactions.inTenant(context -> activationStore.tenantManaged());
    }

    public void appendDefaultDisabled(UUID moduleId, String actor) {
        activationStore.insertDefaultDisabled(moduleId, actor == null ? "" : actor);
    }

    /** 新租户初始化：基础模块目录记录 + 启用行；幂等。 */
    public void provisionBaseModules(UUID tenantId, String actor) {
        RequestContext current = RequestContextHolder.find().orElse(null);
        RequestContext context = new RequestContext(tenantId, actor,
                current == null ? UUID.randomUUID().toString() : current.correlationId(),
                current == null ? newTraceId() : current.traceId());
        transactions.inTenant(context, ctx -> {
            for (ModuleRegistry.Definition definition : ModuleRegistry.definitions()) {
                if (definition.baseModule()) {
                    activationStore.insertBaseModule(definition.code(), definition.name(),
                            actor == null ? "" : actor);
                }
            }
            return null;
        });
        invalidate(tenantId);
    }

    private Set<String> computeEnabled() {
        List<String> all = activationStore.moduleCodes();
        Set<String> disabled = activationStore.disabledCodes();
        return all.stream().filter(code -> !disabled.contains(code))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private AppCatalogStore.ModuleRow requireModule(UUID moduleId) {
        return catalogStore.module(moduleId).orElseThrow(() ->
                ApiException.notFound("APP_MODULE_NOT_FOUND", "模块不存在"));
    }

    private ActivationView view(AppCatalogStore.ModuleRow module,
                                Optional<ModuleActivationStore.ActivationRow> row) {
        return row.map(value -> new ActivationView(module.id(), module.code(), value.status(),
                        value.version(), value.config(), value.enabledAt(), value.enabledBy(),
                        value.disabledAt(), value.disabledBy(), value.updatedAt(), true))
                .orElseGet(() -> new ActivationView(module.id(), module.code(), "enabled", 0L,
                        "{}", null, null, null, null, null, false));
    }

    private void invalidate(UUID tenantId) {
        if (tenantId != null) {
            cache.remove(tenantId);
        }
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
