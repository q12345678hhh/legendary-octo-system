package com.acme.platform.platform.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 作业存储。所有方法都要求调用方已绑定租户上下文（TenantTransactions），
 * 否则 RLS 会让读返回空集、写抛 WITH CHECK 失败。
 */
@Component
public class JobStore {

    private static final String COLUMNS = """
            id, tenant_id, subject_id, job_type, status, progress, cancel_requested,
            payload::text AS payload, result::text AS result, last_error,
            trace_id, correlation_id, requested_at, started_at, finished_at
            """;

    private static final RowMapper<JobRecord> MAPPER = (rs, rowNum) -> new JobRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("subject_id"),
            rs.getString("job_type"),
            rs.getString("status"),
            rs.getInt("progress"),
            rs.getBoolean("cancel_requested"),
            rs.getString("payload"),
            rs.getString("result"),
            rs.getString("last_error"),
            rs.getString("trace_id"),
            rs.getString("correlation_id"),
            rs.getObject("requested_at", java.time.OffsetDateTime.class),
            rs.getObject("started_at", java.time.OffsetDateTime.class),
            rs.getObject("finished_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public JobStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public JobRecord insert(UUID tenantId, String subjectId, String jobType, String payloadJson,
                            String traceId, String correlationId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO platform_job (tenant_id, subject_id, job_type, payload, trace_id, correlation_id)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?)
                RETURNING %s
                """.formatted(COLUMNS),
                MAPPER, tenantId, subjectId, jobType, payloadJson, traceId, correlationId);
    }

    public Optional<JobRecord> find(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM platform_job WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    public List<JobRecord> list(int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM platform_job ORDER BY requested_at DESC LIMIT ?", MAPPER, limit);
    }

    public boolean requestCancel(UUID id) {
        return jdbcTemplate.update("""
                UPDATE platform_job
                   SET cancel_requested = true, updated_at = now()
                 WHERE id = ? AND status IN ('queued', 'running')
                """, id) == 1;
    }

    public void markRunning(UUID id) {
        jdbcTemplate.update("""
                UPDATE platform_job
                   SET status = 'running',
                       progress = GREATEST(progress, 5),
                       started_at = COALESCE(started_at, now()),
                       updated_at = now()
                 WHERE id = ? AND status = 'queued'
                """, id);
    }

    public void updateProgress(UUID id, int progress) {
        jdbcTemplate.update("""
                UPDATE platform_job SET progress = ?, updated_at = now()
                 WHERE id = ? AND status = 'running'
                """, progress, id);
    }

    public boolean cancelRequested(UUID id) {
        Boolean requested = jdbcTemplate.queryForObject(
                "SELECT cancel_requested FROM platform_job WHERE id = ?", Boolean.class, id);
        return Boolean.TRUE.equals(requested);
    }

    public void succeed(UUID id, String resultJson) {
        jdbcTemplate.update("""
                UPDATE platform_job
                   SET status = 'succeeded', progress = 100, result = CAST(? AS jsonb),
                       last_error = NULL, finished_at = now(), updated_at = now()
                 WHERE id = ?
                """, resultJson, id);
    }

    public void cancel(UUID id) {
        jdbcTemplate.update("""
                UPDATE platform_job
                   SET status = 'cancelled', finished_at = now(), updated_at = now()
                 WHERE id = ? AND status IN ('queued', 'running')
                """, id);
    }

    public void fail(UUID id, String error) {
        jdbcTemplate.update("""
                UPDATE platform_job
                   SET status = 'failed', last_error = ?, finished_at = now(), updated_at = now()
                 WHERE id = ? AND status IN ('queued', 'running')
                """, error, id);
    }
}
