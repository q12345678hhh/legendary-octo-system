package com.acme.platform.worker.platformops;

import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.directory.PlatformDirectoryStore;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按租户采集运营指标。
 *
 * <p>所有业务库查询都在租户事务内完成，受 RLS 约束；写回的是平台域的
 * {@code platform_tenant_metric_daily}，只含计数与容量，不含任何业务正文。
 *
 * <p>API 请求数与 AI 成本目前没有可信来源（网关尚未接入），保持 0 并在总览中如实展示，
 * 不做估算或伪造。
 */
@Component
public class TenantMetricCollector {

    private static final Logger log = LoggerFactory.getLogger(TenantMetricCollector.class);
    private static final int RETENTION_DAYS = 400;

    private final PlatformDirectoryStore directoryStore;
    private final TenantTransactions tenantTransactions;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public TenantMetricCollector(PlatformDirectoryStore directoryStore,
                                 TenantTransactions tenantTransactions,
                                 JdbcTemplate jdbcTemplate,
                                 Clock clock) {
        this.directoryStore = directoryStore;
        this.tenantTransactions = tenantTransactions;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${platform.ops.metric-poll-interval:15m}",
            initialDelayString = "${platform.ops.metric-initial-delay:1m}")
    public void collect() {
        LocalDate today = LocalDate.now(clock);
        int collected = 0;
        for (UUID tenantId : directoryStore.tenantIds()) {
            try {
                Metrics metrics = tenantTransactions.inTenant(
                        new RequestContext(tenantId, null, "tenant-metrics",
                                UUID.randomUUID().toString().replace("-", "")),
                        context -> measure());
                directoryStore.upsertMetric(tenantId, today, metrics.activeUsers(), 0, 0,
                        metrics.storageBytes(), 0, 0, metrics.jobsQueued(), metrics.jobsFailed(),
                        metrics.integrationErrors());
                collected++;
            } catch (RuntimeException failure) {
                log.warn("租户 {} 指标采集失败：{}", tenantId, failure.getMessage());
            }
        }
        directoryStore.deleteMetricsBefore(today.minusDays(RETENTION_DAYS));
        if (collected > 0) {
            log.info("平台运营指标：采集 {} 个租户", collected);
        }
    }

    private Metrics measure() {
        long activeUsers = queryLong("""
                SELECT count(*) FROM identity_session
                 WHERE revoked_at IS NULL AND expires_at > now()
                   AND last_seen_at > now() - interval '30 days'
                """);
        long storageBytes = queryLong("""
                SELECT coalesce(sum(size_bytes), 0) FROM platform_file WHERE status = 'available'
                """);
        long jobsQueued = queryLong("""
                SELECT count(*) FROM platform_job WHERE status IN ('queued', 'running')
                """);
        long jobsFailed = queryLong("""
                SELECT count(*) FROM platform_job
                 WHERE status = 'failed' AND updated_at > now() - interval '30 days'
                """);
        long integrationErrors = queryLong("""
                SELECT count(*) FROM platform_outbox WHERE status IN ('failed', 'dead')
                """);
        return new Metrics(activeUsers, storageBytes, jobsQueued, jobsFailed, integrationErrors);
    }

    private long queryLong(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private record Metrics(long activeUsers, long storageBytes, long jobsQueued,
                           long jobsFailed, long integrationErrors) {
    }
}
