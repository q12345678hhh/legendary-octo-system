package com.acme.platform.authorization;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 租户权限缓存版本读取器。
 *
 * <p>数据库是版本事实源（迁移里的触发器递增）；本机只缓存 1 秒，避免每个判定都多一次查询，
 * 又保证跨实例传播远低于 60 秒目标。
 */
@Component
public class CacheVersionStore {

    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationProperties properties;

    private volatile long cachedVersion = -1L;
    private volatile long cachedAtNanos;

    public CacheVersionStore(JdbcTemplate jdbcTemplate, AuthorizationProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public long currentVersion() {
        long now = System.nanoTime();
        long ttlNanos = properties.getVersionTtl().toNanos();
        if (cachedVersion >= 0 && now - cachedAtNanos < ttlNanos) {
            return cachedVersion;
        }
        List<Long> versions = jdbcTemplate.queryForList("""
                SELECT version FROM authorization_cache_version
                """, Long.class);
        long version = versions.isEmpty() ? 0L : versions.getFirst();
        cachedVersion = version;
        cachedAtNanos = now;
        return version;
    }

    /** 高风险撤权后立即丢弃本机版本缓存，下一次判定直接读库。 */
    public void invalidateLocal() {
        cachedAtNanos = 0L;
    }
}
