package com.acme.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 缓存：版本变化立即失效，撤权可精确清主体。 */
class AuthorizationCacheTest {

    private final AuthorizationProperties properties = new AuthorizationProperties();

    @Test
    void cacheHonoursVersionAndInvalidation() {
        properties.setCacheTtl(Duration.ofMinutes(1));
        AuthorizationCache cache = new AuthorizationCache(properties);
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        AccessRequest request = new AccessRequest(tenant, actor, "directory.person.read",
                "directory.person", null, Map.of());
        AccessDecision decision = new AccessDecision(true, DecisionReason.ALLOWED,
                java.util.List.of("GRANT:employee:EMPLOYEE_SELF"), AccessScope.tenant(), Map.of(),
                7L, OffsetDateTime.now(), false);

        String key = cache.key(tenant, actor, request, 7L);
        cache.put(key, decision, 7L);
        assertThat(cache.get(key, 7L)).isPresent();
        assertThat(cache.get(key, 8L)).isEmpty();

        cache.put(key, decision, 7L);
        cache.invalidateActor(actor, tenant);
        assertThat(cache.get(key, 7L)).isEmpty();

        cache.put(key, decision, 7L);
        cache.invalidateAll();
        assertThat(cache.get(key, 7L)).isEmpty();
    }
}
