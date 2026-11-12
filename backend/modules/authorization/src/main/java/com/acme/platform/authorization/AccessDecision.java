package com.acme.platform.authorization;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 判定结果：允许时带可见范围与字段规则，拒绝时带稳定原因码与因素。
 *
 * <p>因素只描述「哪一类规则命中」，不回传策略原文，避免解释器泄露策略结构。
 */
public record AccessDecision(boolean allowed,
                            DecisionReason reason,
                            List<String> factors,
                            AccessScope scope,
                            Map<String, FieldAccess> fieldRules,
                            long cacheVersion,
                            OffsetDateTime evaluatedAt,
                            boolean cacheHit) {

    public AccessDecision {
        factors = factors == null ? List.of() : List.copyOf(factors);
        fieldRules = fieldRules == null ? Map.of() : Map.copyOf(fieldRules);
    }

    public static AccessDecision deny(DecisionReason reason, long version,
                                      OffsetDateTime evaluatedAt, List<String> factors) {
        return new AccessDecision(false, reason, factors, AccessScope.none(), Map.of(),
                version, evaluatedAt, false);
    }

    public AccessDecision withCacheHit(boolean hit) {
        return new AccessDecision(allowed, reason, factors, scope, fieldRules, cacheVersion,
                evaluatedAt, hit);
    }
}
