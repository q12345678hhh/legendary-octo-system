package com.acme.platform.tenancy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 平台支持访问存储：所有查询都在目标租户事务内执行。
 */
@Component
public class SupportAccessStore {

    private static final RowMapper<TenantSupportAccess> MAPPER = (rs, rowNum) -> new TenantSupportAccess(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("ticket_id"),
            rs.getString("operator_id"),
            rs.getString("reason"),
            rs.getInt("requested_minutes"),
            rs.getString("scope"),
            SupportAccessStatus.fromWire(rs.getString("status")),
            rs.getObject("requested_at", OffsetDateTime.class),
            rs.getString("approved_by"),
            rs.getObject("approved_at", OffsetDateTime.class),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("revoked_at", OffsetDateTime.class),
            rs.getObject("last_used_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public SupportAccessStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TenantSupportAccess insert(UUID id, UUID tenantId, String ticketId, String operatorId,
                                      String reason, int requestedMinutes, String scope) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tenancy_support_access
                    (id, tenant_id, ticket_id, operator_id, reason, requested_minutes, scope)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id, tenant_id, ticket_id, operator_id, reason, requested_minutes, scope,
                          status, requested_at, approved_by, approved_at, expires_at, revoked_at, last_used_at
                """, MAPPER, id, tenantId, ticketId, operatorId, reason, requestedMinutes, scope);
    }

    public Optional<TenantSupportAccess> find(UUID tenantId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, ticket_id, operator_id, reason, requested_minutes, scope,
                       status, requested_at, approved_by, approved_at, expires_at, revoked_at, last_used_at
                  FROM tenancy_support_access
                 WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    public List<TenantSupportAccess> list(UUID tenantId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, ticket_id, operator_id, reason, requested_minutes, scope,
                       status, requested_at, approved_by, approved_at, expires_at, revoked_at, last_used_at
                  FROM tenancy_support_access
                 WHERE tenant_id = ?
                 ORDER BY requested_at DESC
                """, MAPPER, tenantId);
    }

    public boolean approve(UUID tenantId, UUID id, String approverId,
                           OffsetDateTime approvedAt, OffsetDateTime expiresAt) {
        int updated = jdbcTemplate.update("""
                UPDATE tenancy_support_access
                   SET status = 'approved', approved_by = ?, approved_at = ?, expires_at = ?
                 WHERE tenant_id = ? AND id = ? AND status = 'pending' AND operator_id <> ?
                """, approverId, approvedAt, expiresAt, tenantId, id, approverId);
        return updated == 1;
    }

    public boolean deny(UUID tenantId, UUID id, String approverId) {
        int updated = jdbcTemplate.update("""
                UPDATE tenancy_support_access
                   SET status = 'denied', approved_by = ?, approved_at = now()
                 WHERE tenant_id = ? AND id = ? AND status = 'pending' AND operator_id <> ?
                """, approverId, tenantId, id, approverId);
        return updated == 1;
    }

    public boolean revoke(UUID tenantId, UUID id) {
        int updated = jdbcTemplate.update("""
                UPDATE tenancy_support_access
                   SET status = 'revoked', revoked_at = now()
                 WHERE tenant_id = ? AND id = ? AND status IN ('pending', 'approved')
                """, tenantId, id);
        return updated == 1;
    }

    public boolean markExpired(UUID tenantId, UUID id) {
        int updated = jdbcTemplate.update("""
                UPDATE tenancy_support_access
                   SET status = 'expired'
                 WHERE tenant_id = ? AND id = ? AND status = 'approved' AND expires_at <= now()
                """, tenantId, id);
        return updated == 1;
    }

    public void touch(UUID tenantId, UUID id) {
        jdbcTemplate.update("""
                UPDATE tenancy_support_access SET last_used_at = now()
                 WHERE tenant_id = ? AND id = ?
                """, tenantId, id);
    }

    /** 最终删除时撤销该租户全部待审批/已批准的支持访问。 */
    public void revokeAll(UUID tenantId) {
        jdbcTemplate.update("""
                UPDATE tenancy_support_access
                   SET status = 'revoked', revoked_at = now()
                 WHERE tenant_id = ? AND status IN ('pending', 'approved')
                """, tenantId);
    }
}
