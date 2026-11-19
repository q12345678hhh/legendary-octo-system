package com.acme.platform.appcatalog;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private record ApiPrefix(String code, String prefix) {
    }

    private record Snapshot(Set<String> codes, List<ApiPrefix> prefixes) {
    }

    private record CacheEntry(Snapshot snapshot, Instant expiresAt) {
    }

    /** 动态注册菜单用的模块路由。 */
    public record ModuleRouteView(String code, String name, String routePath) {
    }

    /** 启停审计事件出参。 */
    public record ActivationEventView(UUID id, String action, String fromStatus, String toStatus,
                                      String actor, String comment, OffsetDateTime occurredAt) {
    }

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

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
            if ("enabled".equals(target)) {
                Set<String> currentlyEnabled = computeSnapshot().codes();
                List<String> missing = dependencyCodes(module.dependencies()).stream()
                        .filter(code -> !currentlyEnabled.contains(code))
                        .distinct()
                        .toList();
                if (!missing.isEmpty()) {
                    throw ApiException.conflict("MODULE_DEPENDENCY_DISABLED",
                            "请先启用依赖模块：" + String.join("、", missing));
                }
            } else {
                List<String> dependents = activationStore.enabledDependents(module.code());
                if (!dependents.isEmpty()) {
                    throw ApiException.conflict("MODULE_DEPENDENT_ENABLED",
                            "以下模块仍依赖此模块，请先停用：" + String.join("、", dependents));
                }
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

    /** 模块启停历史，只读；模块不存在时 404。 */
    public List<ActivationEventView> events(UUID moduleId) {
        return transactions.inTenant(context -> {
            requireModule(moduleId);
            return activationStore.events(moduleId).stream()
                    .map(row -> new ActivationEventView(row.id(), row.action(), row.fromStatus(),
                            row.toStatus(), row.actor(), row.comment(), row.occurredAt()))
                    .toList();
        });
    }

    /**
     * 当前租户实际启用的模块 code：所有已登记模块 + 目录模块减去显式停用。
     *
     * <p>记录缺失一律视为启用，因此存量租户即使目录里没有基础模块记录，也不会因为
     * 能力清单缺项而被前端隐藏或后端拦截。只有显式 disabled 才真正关闭。
     */
    public Set<String> enabledModuleCodes() {
        return snapshot().codes();
    }

    /** 请求路径命中的自定义模块 code（模块登记了 api_path_prefix 才参与）。 */
    public Optional<String> moduleForPath(String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        String matchedCode = null;
        int matchedLength = -1;
        for (ApiPrefix prefix : snapshot().prefixes()) {
            String value = prefix.prefix();
            if (path.startsWith(value) && (path.length() == value.length()
                    || path.charAt(value.length()) == '/') && value.length() > matchedLength) {
                matchedCode = prefix.code();
                matchedLength = value.length();
            }
        }
        return Optional.ofNullable(matchedCode);
    }

    /** 已启用且登记了前端路由的模块，供能力清单动态注册菜单。 */
    public List<ModuleRouteView> enabledModuleRoutes() {
        return transactions.inTenant(context -> {
            Set<String> disabled = activationStore.disabledCodes();
            return catalogStore.modulesWithRoute().stream()
                    .filter(row -> !disabled.contains(row.code()))
                    .map(row -> new ModuleRouteView(row.code(), row.name(), row.path()))
                    .toList();
        });
    }

    /** 目录中的模块路由/前缀变化后立即失效本机缓存。 */
    public void invalidateCurrentTenant() {
        RequestContextHolder.find().map(RequestContext::tenantId).ifPresent(this::invalidate);
    }

    private Snapshot snapshot() {
        RequestContext context = RequestContextHolder.find().orElse(null);
        if (context == null || context.tenantId() == null) {
            // 没有租户上下文时不能误拦截；调用方（过滤器）在认证之后才调用本方法。
            return new Snapshot(Set.copyOf(ModuleRegistry.codes()), List.of());
        }
        UUID tenantId = context.tenantId();
        CacheEntry entry = cache.get(tenantId);
        Instant now = Instant.now();
        if (entry != null && entry.expiresAt().isAfter(now)) {
            return entry.snapshot();
        }
        Snapshot snapshot = transactions.inTenant(ctx -> computeSnapshot());
        cache.put(tenantId, new CacheEntry(snapshot, now.plus(CACHE_TTL)));
        return snapshot;
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

    /**
     * 已实现模块默认启用，再叠加目录里显式启用的模块，最后剔除显式停用。
     *
     * <p>存量租户的目录里可能没有基础模块记录（迁移只回填了既有行），
     * 因此不能只按目录求差集，否则基础模块会被前端误判为停用而整片消失。
     */
    private Snapshot computeSnapshot() {
        Set<String> enabled = new LinkedHashSet<>(ModuleRegistry.codes());
        enabled.addAll(activationStore.moduleCodes());
        enabled.removeAll(activationStore.disabledCodes());
        List<ApiPrefix> prefixes = catalogStore.modulesWithApiPrefix().stream()
                .map(row -> new ApiPrefix(row.code(), row.path()))
                .toList();
        return new Snapshot(Set.copyOf(enabled), prefixes);
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

    private static List<String> dependencyCodes(String dependencies) {
        if (dependencies == null || dependencies.isBlank()) {
            return List.of();
        }
        Matcher matcher = QUOTED.matcher(dependencies);
        List<String> codes = new java.util.ArrayList<>();
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        return codes;
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
