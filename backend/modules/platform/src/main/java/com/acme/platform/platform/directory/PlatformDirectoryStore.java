package com.acme.platform.platform.directory;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 租户目录投影与按日指标。
 *
 * <p>两张表都故意不启用 RLS：平台控制面必须跨租户读，但字段只限生命周期元数据与聚合计数。
 * 业务正文（人员、文件内容、工时、消息）永远不会写入这里。
 */
@Component
public class PlatformDirectoryStore {

    public record TenantSummary(UUID id, String slug, String name, String status, String planCode,
                                OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record MetricAggregate(long activeUsers, long apiRequests, long errorRequests,
                                  long storageBytes, long aiTokens, long aiCostCents,
                                  long jobsQueued, long jobsFailed, long integrationErrors,
                                  long activeTenants, OffsetDateTime collectedAt) {
    }

    private static final RowMapper<TenantSummary> MAPPER = (rs, rowNum) -> new TenantSummary(
            rs.getObject("id", UUID.class),
            rs.getString("slug"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getString("plan_code"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public PlatformDirectoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TenantSummary> list(String status, String query, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, slug, name, status, plan_code, created_at, updated_at
                  FROM platform_tenant_directory
                 WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (name ILIKE ? OR slug ILIKE ?)");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 200)));
        return jdbcTemplate.query(sql.toString(), MAPPER, params.toArray());
    }

    public Optional<TenantSummary> find(UUID tenantId) {
        return jdbcTemplate.query("""
                SELECT id, slug, name, status, plan_code, created_at, updated_at
                  FROM platform_tenant_directory WHERE id = ?
                """, MAPPER, tenantId).stream().findFirst();
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT status, count(*) AS total FROM platform_tenant_directory GROUP BY status
                 ORDER BY status
                """, rs -> {
            counts.put(rs.getString("status"), rs.getLong("total"));
        });
        return counts;
    }

    public long count() {
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform_tenant_directory", Long.class);
        return total == null ? 0 : total;
    }

    public List<UUID> tenantIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM platform_tenant_directory ORDER BY created_at", UUID.class);
    }

    public void upsertMetric(UUID tenantId, LocalDate day, long activeUsers, long apiRequests,
                             long errorRequests, long storageBytes, long aiTokens,
                             long aiCostCents, long jobsQueued, long jobsFailed,
                             long integrationErrors) {
        jdbcTemplate.update("""
                INSERT INTO platform_tenant_metric_daily
                    (tenant_id, day, active_users, api_requests, error_requests, storage_bytes,
                     ai_tokens, ai_cost_cents, jobs_queued, jobs_failed, integration_errors,
                     collected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (tenant_id, day) DO UPDATE
                   SET active_users = EXCLUDED.active_users,
                       api_requests = EXCLUDED.api_requests,
                       error_requests = EXCLUDED.error_requests,
                       storage_bytes = EXCLUDED.storage_bytes,
                       ai_tokens = EXCLUDED.ai_tokens,
                       ai_cost_cents = EXCLUDED.ai_cost_cents,
                       jobs_queued = EXCLUDED.jobs_queued,
                       jobs_failed = EXCLUDED.jobs_failed,
                       integration_errors = EXCLUDED.integration_errors,
                       collected_at = now()
                """, tenantId, day, activeUsers, apiRequests, errorRequests, storageBytes,
                aiTokens, aiCostCents, jobsQueued, jobsFailed, integrationErrors);
    }

    public MetricAggregate aggregateSince(LocalDate from) {
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(active_users), 0)      AS active_users,
                       coalesce(sum(api_requests), 0)      AS api_requests,
                       coalesce(sum(error_requests), 0)    AS error_requests,
                       coalesce(sum(storage_bytes), 0)     AS storage_bytes,
                       coalesce(sum(ai_tokens), 0)         AS ai_tokens,
                       coalesce(sum(ai_cost_cents), 0)     AS ai_cost_cents,
                       coalesce(sum(jobs_queued), 0)       AS jobs_queued,
                       coalesce(sum(jobs_failed), 0)       AS jobs_failed,
                       coalesce(sum(integration_errors), 0) AS integration_errors,
                       count(DISTINCT tenant_id)
                           FILTER (WHERE active_users > 0) AS active_tenants,
                       max(collected_at)                   AS collected_at
                  FROM platform_tenant_metric_daily
                 WHERE day >= ?
                """, (rs, rowNum) -> new MetricAggregate(
                rs.getLong("active_users"), rs.getLong("api_requests"),
                rs.getLong("error_requests"), rs.getLong("storage_bytes"),
                rs.getLong("ai_tokens"), rs.getLong("ai_cost_cents"),
                rs.getLong("jobs_queued"), rs.getLong("jobs_failed"),
                rs.getLong("integration_errors"), rs.getLong("active_tenants"),
                rs.getObject("collected_at", OffsetDateTime.class)), from);
    }

    public void deleteMetricsBefore(LocalDate day) {
        jdbcTemplate.update("DELETE FROM platform_tenant_metric_daily WHERE day < ?", day);
    }
}
