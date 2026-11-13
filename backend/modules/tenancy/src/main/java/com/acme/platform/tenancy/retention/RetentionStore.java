package com.acme.platform.tenancy.retention;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 回收站的 JDBC 存储。
 *
 * <p>只负责 SQL；调用方必须在 {@code TenantTransactions.inTenant(...)} 内执行，
 * 否则 RLS 会让读写返回 0 行。
 */
@Component
public class RetentionStore {

    private static final RowMapper<RetentionBinItem> MAPPER = (rs, rowNum) -> new RetentionBinItem(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("data_class"),
            rs.getObject("object_id", UUID.class),
            rs.getString("object_ref"),
            rs.getString("snapshot"),
            rs.getString("status"),
            rs.getString("deleted_by"),
            rs.getObject("deleted_at", OffsetDateTime.class),
            rs.getObject("purge_after", OffsetDateTime.class),
            rs.getObject("restored_at", OffsetDateTime.class),
            rs.getString("restored_by"),
            rs.getObject("purged_at", OffsetDateTime.class),
            rs.getObject("anonymized_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public RetentionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RetentionBinItem> findActive(UUID tenantId, String dataClass, UUID objectId) {
        return jdbcTemplate.query("""
                        SELECT * FROM retention_bin_item
                        WHERE tenant_id = ? AND data_class = ? AND object_id = ? AND status = 'active'
                        """, MAPPER, tenantId, dataClass, objectId)
                .stream().findFirst();
    }

    public Optional<RetentionBinItem> find(UUID id) {
        return jdbcTemplate.query("SELECT * FROM retention_bin_item WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<RetentionBinItem> list(UUID tenantId, int limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM retention_bin_item
                        WHERE tenant_id = ?
                        ORDER BY deleted_at DESC
                        LIMIT ?
                        """, MAPPER, tenantId, limit);
    }

    public RetentionBinItem insert(UUID tenantId, String dataClass, UUID objectId, String objectRef,
                                   String snapshotJson, String deletedBy, int retentionDays) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO retention_bin_item
                            (tenant_id, data_class, object_id, object_ref, snapshot, deleted_by, purge_after)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, now() + make_interval(days => ?))
                        RETURNING *
                        """, MAPPER, tenantId, dataClass, objectId, objectRef, snapshotJson, deletedBy,
                retentionDays);
    }

    public int markRestored(UUID id, String actor) {
        return jdbcTemplate.update("""
                UPDATE retention_bin_item
                SET status = 'restored', restored_at = now(), restored_by = ?
                WHERE id = ? AND status = 'active'
                """, actor, id);
    }

    public int markPurged(UUID id) {
        return jdbcTemplate.update("""
                UPDATE retention_bin_item
                SET status = 'purged', purged_at = now()
                WHERE id = ? AND status IN ('active', 'anonymized')
                """, id);
    }

    public int markAnonymized(UUID id) {
        return jdbcTemplate.update("""
                UPDATE retention_bin_item
                SET status = 'anonymized', anonymized_at = now()
                WHERE id = ? AND status = 'active'
                """, id);
    }

    public List<RetentionBinItem> due(UUID tenantId, int limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM retention_bin_item
                        WHERE tenant_id = ? AND status = 'active' AND purge_after <= now()
                        ORDER BY purge_after
                        LIMIT ?
                        """, MAPPER, tenantId, limit);
    }
}
