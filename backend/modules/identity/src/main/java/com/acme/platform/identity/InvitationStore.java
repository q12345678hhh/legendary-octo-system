package com.acme.platform.identity;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 邀请/激活记录的 JDBC 存储。 */
@Component
public class InvitationStore {

    private static final RowMapper<Invitation> MAPPER = (rs, rowNum) -> new Invitation(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("member_id", UUID.class),
            rs.getString("email"),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("accepted_at", OffsetDateTime.class),
            rs.getObject("revoked_at", OffsetDateTime.class));

    private static final String SELECT = """
            SELECT id, tenant_id, user_id, member_id, email, expires_at, accepted_at, revoked_at
              FROM identity_invitation
            """;

    private final JdbcTemplate jdbcTemplate;

    public InvitationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID id, UUID userId, UUID memberId, String email, String tokenHash,
                       OffsetDateTime expiresAt, String createdBy) {
        jdbcTemplate.update("""
                INSERT INTO identity_invitation
                    (id, tenant_id, user_id, member_id, email, token_hash, expires_at, created_by)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
                """, id, userId, memberId, email, tokenHash, expiresAt, createdBy);
    }

    public Optional<Invitation> findByTokenHash(String tokenHash) {
        return jdbcTemplate.query(SELECT + " WHERE token_hash = ?", MAPPER, tokenHash)
                .stream().findFirst();
    }

    public void markAccepted(UUID id) {
        jdbcTemplate.update("""
                UPDATE identity_invitation SET accepted_at = now() WHERE id = ?
                """, id);
    }

    public void revokeActiveForMember(UUID memberId) {
        jdbcTemplate.update("""
                UPDATE identity_invitation SET revoked_at = now()
                 WHERE member_id = ? AND accepted_at IS NULL AND revoked_at IS NULL
                """, memberId);
    }
}
