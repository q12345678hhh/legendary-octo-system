package com.acme.platform.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 会话的 JDBC 存储。 */
@Component
public class SessionStore {

    private static final RowMapper<SessionRecord> MAPPER = (rs, rowNum) -> new SessionRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("actor_id", UUID.class),
            rs.getString("token_hash"),
            rs.getInt("auth_version"),
            rs.getBoolean("mfa_satisfied"),
            rs.getObject("device_id", UUID.class),
            rs.getString("device_label"),
            rs.getString("ip"),
            rs.getString("user_agent"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("last_seen_at", OffsetDateTime.class),
            rs.getObject("idle_expires_at", OffsetDateTime.class),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("revoked_at", OffsetDateTime.class));

    private static final String SELECT = """
            SELECT id, tenant_id, user_id, actor_id, token_hash, auth_version, mfa_satisfied,
                   device_id, device_label, ip, user_agent, created_at, last_seen_at,
                   idle_expires_at, expires_at, revoked_at
              FROM identity_session
            """;

    private final JdbcTemplate jdbcTemplate;

    public SessionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID id, UUID userId, UUID actorId, String tokenHash, int authVersion,
                       boolean mfaSatisfied, UUID deviceId, String deviceLabel, String ip,
                       String userAgent, OffsetDateTime idleExpiresAt, OffsetDateTime expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO identity_session
                    (id, tenant_id, user_id, actor_id, token_hash, auth_version, mfa_satisfied,
                     device_id, device_label, ip, user_agent, idle_expires_at, expires_at)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?)
                """, id, userId, actorId, tokenHash, authVersion, mfaSatisfied, deviceId,
                deviceLabel, ip, userAgent, idleExpiresAt, expiresAt);
    }

    public Optional<SessionRecord> findByTokenHash(String tokenHash) {
        return jdbcTemplate.query(SELECT + " WHERE token_hash = ?", MAPPER, tokenHash)
                .stream().findFirst();
    }

    public Optional<SessionRecord> findById(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<SessionRecord> listActiveByUser(UUID userId) {
        return jdbcTemplate.query(SELECT + """
                 WHERE user_id = ? AND revoked_at IS NULL
                   AND expires_at > now() AND idle_expires_at > now()
                 ORDER BY last_seen_at DESC
                """, MAPPER, userId);
    }

    public void touch(UUID id, OffsetDateTime lastSeenAt, OffsetDateTime idleExpiresAt) {
        jdbcTemplate.update("""
                UPDATE identity_session SET last_seen_at = ?, idle_expires_at = ? WHERE id = ?
                """, lastSeenAt, idleExpiresAt, id);
    }

    public void markMfaSatisfied(UUID id) {
        jdbcTemplate.update("""
                UPDATE identity_session SET mfa_satisfied = true WHERE id = ?
                """, id);
    }

    public void revoke(UUID id, String reason, String revokedBy) {
        jdbcTemplate.update("""
                UPDATE identity_session
                   SET revoked_at = now(), revoked_reason = ?, revoked_by = ?
                 WHERE id = ? AND revoked_at IS NULL
                """, reason, revokedBy, id);
    }

    public void revokeAllForUser(UUID userId, String reason, String revokedBy) {
        jdbcTemplate.update("""
                UPDATE identity_session
                   SET revoked_at = now(), revoked_reason = ?, revoked_by = ?
                 WHERE user_id = ? AND revoked_at IS NULL
                """, reason, revokedBy, userId);
    }

    public void revokeByDevice(UUID userId, UUID deviceId, String reason, String revokedBy) {
        jdbcTemplate.update("""
                UPDATE identity_session
                   SET revoked_at = now(), revoked_reason = ?, revoked_by = ?
                 WHERE user_id = ? AND device_id = ? AND revoked_at IS NULL
                """, reason, revokedBy, userId, deviceId);
    }
}
