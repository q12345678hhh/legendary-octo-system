package com.acme.platform.platform.retention;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 数据主体请求案件的 JDBC 存储（平台表，无 RLS）。
 *
 * <p>跨租户隔离由调用方按 {@code tenant_id} 过滤；租户侧接口只能用
 * {@link #listByTenant(UUID, int)} 与 {@link #find(UUID)} + 归属校验。
 */
@Component
public class DeletionRequestStore {

    private static final RowMapper<DeletionRequest> MAPPER = (rs, rowNum) -> new DeletionRequest(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("subject_type"),
            rs.getObject("subject_id", UUID.class),
            rs.getString("subject_hash"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("decision_code"),
            rs.getString("decision_note"),
            rs.getString("requested_by"),
            rs.getObject("requested_at", OffsetDateTime.class),
            rs.getString("decided_by"),
            rs.getObject("decided_at", OffsetDateTime.class),
            rs.getString("executed_by"),
            rs.getObject("executed_at", OffsetDateTime.class),
            rs.getObject("propagated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public DeletionRequestStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DeletionRequest insert(UUID tenantId, String subjectType, UUID subjectId, String subjectHash,
                                  String reason, String requestedBy) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO platform_deletion_request
                            (tenant_id, subject_type, subject_id, subject_hash, reason, requested_by)
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING *
                        """, MAPPER, tenantId, subjectType, subjectId, subjectHash, reason, requestedBy);
    }

    public Optional<DeletionRequest> find(UUID id) {
        return jdbcTemplate.query("SELECT * FROM platform_deletion_request WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<DeletionRequest> listByTenant(UUID tenantId, int limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM platform_deletion_request
                        WHERE tenant_id = ?
                        ORDER BY requested_at DESC
                        LIMIT ?
                        """, MAPPER, tenantId, limit);
    }

    public List<DeletionRequest> listByStatus(String status, int limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM platform_deletion_request
                        WHERE status = ?
                        ORDER BY requested_at
                        LIMIT ?
                        """, MAPPER, status, limit);
    }

    public int markVerifying(UUID id) {
        return jdbcTemplate.update(
                "UPDATE platform_deletion_request SET status = 'verifying' WHERE id = ? AND status = 'received'",
                id);
    }

    public int markApproved(UUID id, String decisionCode, String note, String decidedBy) {
        return jdbcTemplate.update("""
                UPDATE platform_deletion_request
                SET status = 'approved', decision_code = ?, decision_note = ?, decided_by = ?, decided_at = now()
                WHERE id = ? AND status IN ('received', 'verifying')
                """, decisionCode, note, decidedBy, id);
    }

    public int markRejected(UUID id, String decisionCode, String note, String decidedBy) {
        return jdbcTemplate.update("""
                UPDATE platform_deletion_request
                SET status = 'rejected', decision_code = ?, decision_note = ?, decided_by = ?, decided_at = now()
                WHERE id = ? AND status IN ('received', 'verifying')
                """, decisionCode, note, decidedBy, id);
    }

    public int markExecuting(UUID id, String executedBy) {
        return jdbcTemplate.update("""
                UPDATE platform_deletion_request
                SET status = 'executing', executed_by = ?
                WHERE id = ? AND status = 'approved'
                """, executedBy, id);
    }

    public int markCompleted(UUID id, OffsetDateTime propagatedAt) {
        return jdbcTemplate.update("""
                UPDATE platform_deletion_request
                SET status = 'completed', executed_at = now(), propagated_at = ?
                WHERE id = ? AND status = 'executing'
                """, propagatedAt, id);
    }

    public int markFailed(UUID id, String note) {
        return jdbcTemplate.update("""
                UPDATE platform_deletion_request
                SET status = 'failed', decision_note = ?
                WHERE id = ? AND status IN ('approved', 'executing')
                """, note, id);
    }

    public int markCancelled(UUID id) {
        return jdbcTemplate.update("""
                UPDATE platform_deletion_request
                SET status = 'cancelled'
                WHERE id = ? AND status IN ('received', 'verifying')
                """, id);
    }
}
