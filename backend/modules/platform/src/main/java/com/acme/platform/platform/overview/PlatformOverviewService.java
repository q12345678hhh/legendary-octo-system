package com.acme.platform.platform.overview;

import com.acme.platform.platform.directory.PlatformDirectoryStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 运营总览：只读平台元数据表、outbox 状态与进程内 HTTP 计数。
 *
 * <p>不查询任何租户业务表，也不接受租户头；所有跨租户数字都来自 Worker 预聚合的
 * {@code platform_tenant_metric_daily}，因此即使 SQL 写错也拿不到业务正文。
 */
@Service
public class PlatformOverviewService {

    public record ConnectorHealth(String connectorCode, String name, String status) {
    }

    public record Overview(OffsetDateTime generatedAt,
                           Map<String, Long> tenantsByStatus,
                           long tenantTotal,
                           long activeTenants30d,
                           long activeUsers30d,
                           long storageBytes,
                           long aiTokens30d,
                           long aiCostCents30d,
                           long apiRequests30d,
                           long errorRequests30d,
                           double httpErrorRate,
                           long outboxPending,
                           long outboxFailed,
                           long outboxDead,
                           long securityOpen,
                           List<ConnectorHealth> connectors,
                           OffsetDateTime metricsCollectedAt,
                           boolean stale) {
    }

    private static final long STALE_THRESHOLD_MINUTES = 30;

    private final PlatformDirectoryStore directoryStore;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public PlatformOverviewService(PlatformDirectoryStore directoryStore,
                                   JdbcTemplate jdbcTemplate,
                                   MeterRegistry meterRegistry,
                                   Clock clock) {
        this.directoryStore = directoryStore;
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public Overview overview() {
        LocalDate from = LocalDate.now(clock).minusDays(30);
        PlatformDirectoryStore.MetricAggregate metrics = directoryStore.aggregateSince(from);
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean stale = metrics.collectedAt() == null
                || metrics.collectedAt().isBefore(now.minusMinutes(STALE_THRESHOLD_MINUTES));

        return new Overview(
                now,
                directoryStore.countByStatus(),
                directoryStore.count(),
                metrics.activeTenants(),
                metrics.activeUsers(),
                metrics.storageBytes(),
                metrics.aiTokens(),
                metrics.aiCostCents(),
                metrics.apiRequests(),
                metrics.errorRequests(),
                httpErrorRate(),
                count("SELECT count(*) FROM platform_outbox WHERE status = 'pending'"),
                count("SELECT count(*) FROM platform_outbox WHERE status = 'failed'"),
                count("SELECT count(*) FROM platform_outbox WHERE status = 'dead'"),
                count("SELECT count(*) FROM platform_security_event WHERE status = 'open'"),
                connectors(),
                metrics.collectedAt(),
                stale);
    }

    private double httpErrorRate() {
        double total = 0;
        double errors = 0;
        for (Counter counter : meterRegistry.find("platform.http.requests").counters()) {
            double value = counter.count();
            total += value;
            String status = counter.getId().getTag("status");
            if (status != null) {
                try {
                    if (Integer.parseInt(status) >= 400) {
                        errors += value;
                    }
                } catch (NumberFormatException ignored) {
                    // 非数字状态标签不参与错误率计算
                }
            }
        }
        return total == 0 ? 0 : Math.round(errors / total * 10000d) / 10000d;
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private List<ConnectorHealth> connectors() {
        return jdbcTemplate.query("""
                SELECT connector_code, name, status FROM platform_connector ORDER BY connector_code
                """, (rs, rowNum) -> new ConnectorHealth(rs.getString("connector_code"),
                rs.getString("name"), rs.getString("status")));
    }
}
