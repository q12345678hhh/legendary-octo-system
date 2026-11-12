package com.acme.platform.authorization;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 判定结果的本机缓存：key 带租户与版本，版本变化后旧条目自然失效。
 *
 * <p>TTL 默认 60 秒；命中时仍要求条目版本等于当前版本，避免缓存拖过撤权。
 */
@Component
public class AuthorizationCache {

    private record Entry(AccessDecision decision, long expiresAtNanos) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AuthorizationProperties properties;

    public AuthorizationCache(AuthorizationProperties properties) {
        this.properties = properties;
    }

    public Optional<AccessDecision> get(String key, long version) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtNanos() <= System.nanoTime()
                || entry.decision().cacheVersion() != version) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.decision().withCacheHit(true));
    }

    public void put(String key, AccessDecision decision, long version) {
        if (entries.size() > 4096) {
            long now = System.nanoTime();
            entries.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
        }
        long expiresAt = System.nanoTime() + properties.getCacheTtl().toNanos();
        entries.put(key, new Entry(new AccessDecision(decision.allowed(), decision.reason(),
                decision.factors(), decision.scope(), decision.fieldRules(), version,
                decision.evaluatedAt(), false), expiresAt));
    }

    public String key(UUID tenantId, UUID actorId, AccessRequest request, long version) {
        StringBuilder builder = new StringBuilder()
                .append(tenantId).append('|')
                .append(version).append('|')
                .append(actorId).append('|')
                .append(request.permission()).append('|')
                .append(request.resourceType()).append('|')
                .append(request.resourceId()).append('|');
        new TreeMap<>(request.attributes()).forEach((key, value) ->
                builder.append(key).append('=').append(value).append(','));
        return builder.toString();
    }

    /** 撤权时清掉该主体的全部缓存条目；版本号仍由数据库递增。 */
    public void invalidateActor(UUID actorId, UUID tenantId) {
        String prefix = tenantId + "|";
        entries.keySet().removeIf(key -> key.startsWith(prefix) && key.contains("|" + actorId + "|"));
    }

    public void invalidateAll() {
        entries.clear();
    }
}
