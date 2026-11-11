package com.acme.platform.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 租户成员的 JDBC 存储。 */
@Component
public class MemberStore {

    private static final RowMapper<TenantMember> MAPPER = (rs, rowNum) -> new TenantMember(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("person_id", UUID.class),
            MemberStatus.fromWire(rs.getString("status")),
            rs.getBoolean("is_owner"),
            rs.getBoolean("is_admin"),
            rs.getBoolean("mfa_required"));

    private static final String SELECT = """
            SELECT id, tenant_id, user_id, person_id, status, is_owner, is_admin, mfa_required
              FROM identity_tenant_member
            """;

    private final JdbcTemplate jdbcTemplate;

    public MemberStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID id, UUID userId, UUID personId, MemberStatus status,
                       boolean owner, boolean admin, boolean mfaRequired) {
        jdbcTemplate.update("""
                INSERT INTO identity_tenant_member
                    (id, tenant_id, user_id, person_id, status, is_owner, is_admin, mfa_required)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
                """, id, userId, personId, status.wire(), owner, admin, mfaRequired);
    }

    public Optional<TenantMember> findById(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<TenantMember> findByUserId(UUID userId) {
        return jdbcTemplate.query(SELECT + " WHERE user_id = ?", MAPPER, userId).stream().findFirst();
    }

    public Optional<TenantMember> findByPersonId(UUID personId) {
        return jdbcTemplate.query(SELECT + " WHERE person_id = ?", MAPPER, personId).stream().findFirst();
    }

    public List<TenantMember> list(int limit) {
        return jdbcTemplate.query(SELECT + " ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }

    public void updateStatus(UUID id, MemberStatus status) {
        jdbcTemplate.update("""
                UPDATE identity_tenant_member
                   SET status = ?, updated_at = now()
                 WHERE id = ?
                """, status.wire(), id);
    }

    public void updateFlags(UUID id, Boolean admin, Boolean mfaRequired) {
        jdbcTemplate.update("""
                UPDATE identity_tenant_member
                   SET is_admin = COALESCE(?, is_admin),
                       mfa_required = COALESCE(?, mfa_required),
                       updated_at = now()
                 WHERE id = ?
                """, admin, mfaRequired, id);
    }

    public void updateUserId(UUID id, UUID userId) {
        jdbcTemplate.update("""
                UPDATE identity_tenant_member SET user_id = ?, updated_at = now() WHERE id = ?
                """, userId, id);
    }

    public void insertAccountHistory(UUID memberId, UUID previousUserId, UUID newUserId,
                                     String changedBy, String reason) {
        jdbcTemplate.update("""
                INSERT INTO identity_member_account_history
                    (tenant_id, member_id, previous_user_id, new_user_id, changed_by, reason)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
                """, memberId, previousUserId, newUserId, changedBy, reason);
    }
}
