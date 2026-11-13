package com.acme.platform.platform.operations;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 平台运行记录与平台审计查询。 */
@Component
public class PlatformOperationStore {

    public record OperationRun(UUID id, String kind, UUID tenantId, String status,
                               OffsetDateTime startedAt, OffsetDateTime finishedAt, String summary,
                               UUID operatorId, String correlationId, String traceId) {
    }

    public record AuditEvent(UUID id, UUID operatorId, String action, String targetType,
                             String targetId, String detail, String correlationId, String traceId,
                             OffsetDateTime occurredAt) {
    }

    private static final RowMapper<OperationRun> RUN_MAPPER = (rs, rowNum) -> new OperationRun(
            rs.getObject("id", UUID.class), rs.getString("kind"),
            rs.getObject("tenant_id", UUID.class), rs.getString("status"),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class), rs.getString("summary"),
            rs.getObject("operator_id", UUID.class), rs.getString("correlation_id"),
            rs.getString("trace_id"));

    private static final RowMapper<AuditEvent> AUDIT_MAPPER = (rs, rowNum) -> new AuditEvent(
            rs.getObject("id", UUID.class), rs.getObject("operator_id", UUID.class),
            rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
            rs.getString("detail"), rs.getString("correlation_id"), rs.getString("trace_id"),
            rs.getObject("occurred_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public PlatformOperationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID insert(String kind, UUID tenantId, String status, String summary, UUID operatorId,
                       String correlationId, String traceId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform_operation_run
                    (id, kind, tenant_id, status, summary, operator_id, correlation_id, trace_id,
                     finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'running' THEN NULL ELSE now() END)
                """, id, kind, tenantId, status, summary, operatorId, correlationId, traceId, status);
        return id;
    }

    public Optional<OperationRun> latest(String kind) {
        return jdbcTemplate.query("""
                SELECT id, kind, tenant_id, status, started_at, finished_at, summary, operator_id,
                       correlation_id, trace_id
                  FROM platform_operation_run WHERE kind = ?
                 ORDER BY started_at DESC LIMIT 1
                """, RUN_MAPPER, kind).stream().findFirst();
    }

    public List<OperationRun> list(String kind, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, kind, tenant_id, status, started_at, finished_at, summary, operator_id,
                       correlation_id, trace_id
                  FROM platform_operation_run
                """);
        List<Object> params = new ArrayList<>();
        if (kind != null && !kind.isBlank()) {
            sql.append(" WHERE kind = ?");
            params.add(kind);
        }
        sql.append(" ORDER BY started_at DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 200)));
        return jdbcTemplate.query(sql.toString(), RUN_MAPPER, params.toArray());
    }

    public List<AuditEvent> audit(int limit) {
        return jdbcTemplate.query("""
                SELECT id, operator_id, action, target_type, target_id, detail, correlation_id,
                       trace_id, occurred_at
                  FROM platform_audit_event ORDER BY occurred_at DESC LIMIT ?
                """, AUDIT_MAPPER, Math.max(1, Math.min(limit, 200)));
    }

    public long countOutbox(String status) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform_outbox WHERE status = ?", Long.class, status);
        return value == null ? 0 : value;
    }
}
