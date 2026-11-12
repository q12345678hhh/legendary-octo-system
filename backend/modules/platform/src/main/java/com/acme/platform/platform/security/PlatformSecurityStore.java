package com.acme.platform.platform.security;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 安全事件台账存储。
 *
 * <p>该表故意不启用 RLS：平台安全运营必须跨租户查看「哪个租户出现了异常」，
 * 但只读摘要与计数，凭据和业务正文一律不入表。
 */
@Component
public class PlatformSecurityStore {

    public record SecurityEvent(UUID id, UUID tenantId, String category, String severity,
                                String status, String summary, String detail,
                                OffsetDateTime occurredAt, String acknowledgedBy,
                                OffsetDateTime acknowledgedAt, String resolvedBy,
                                OffsetDateTime resolvedAt) {
    }

    private static final RowMapper<SecurityEvent> MAPPER = (rs, rowNum) -> new SecurityEvent(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("category"),
            rs.getString("severity"),
            rs.getString("status"),
            rs.getString("summary"),
            rs.getString("detail"),
            rs.getObject("occurred_at", OffsetDateTime.class),
            rs.getString("acknowledged_by"),
            rs.getObject("acknowledged_at", OffsetDateTime.class),
            rs.getString("resolved_by"),
            rs.getObject("resolved_at", OffsetDateTime.class));

    private static final String SELECT = """
            SELECT id, tenant_id, category, severity, status, summary, detail, occurred_at,
                   acknowledged_by, acknowledged_at, resolved_by, resolved_at
              FROM platform_security_event
            """;

    private final JdbcTemplate jdbcTemplate;

    public PlatformSecurityStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID insert(UUID tenantId, String category, String severity, String summary,
                       String detailJson, OffsetDateTime occurredAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform_security_event
                    (id, tenant_id, category, severity, summary, detail, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, id, tenantId, category, severity, summary, detailJson, occurredAt);
        return id;
    }

    /** 同一租户同一类别在窗口内只登记一次，避免 Worker 每轮重复刷屏。 */
    public boolean insertIfAbsent(UUID tenantId, String category, OffsetDateTime since,
                                  String severity, String summary, String detailJson) {
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform_security_event
                 WHERE tenant_id IS NOT DISTINCT FROM ? AND category = ? AND occurred_at >= ?
                """, Integer.class, tenantId, category, since);
        if (existing != null && existing > 0) {
            return false;
        }
        insert(tenantId, category, severity, summary, detailJson, OffsetDateTime.now());
        return true;
    }

    public Optional<SecurityEvent> find(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<SecurityEvent> list(String category, String status, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            params.add(category);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 200)));
        return jdbcTemplate.query(sql.toString(), MAPPER, params.toArray());
    }

    public boolean acknowledge(UUID id, String actor) {
        return jdbcTemplate.update("""
                UPDATE platform_security_event
                   SET status = 'acknowledged', acknowledged_by = ?, acknowledged_at = now()
                 WHERE id = ? AND status = 'open'
                """, actor, id) == 1;
    }

    public boolean resolve(UUID id, String actor) {
        return jdbcTemplate.update("""
                UPDATE platform_security_event
                   SET status = 'resolved', resolved_by = ?, resolved_at = now(),
                       acknowledged_by = coalesce(acknowledged_by, ?),
                       acknowledged_at = coalesce(acknowledged_at, now())
                 WHERE id = ? AND status <> 'resolved'
                """, actor, actor, id) == 1;
    }
}
