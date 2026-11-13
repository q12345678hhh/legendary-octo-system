package com.acme.platform.identity.operator;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 平台运营会话的 JDBC 存储。 */
@Component
public class PlatformOperatorSessionStore {

    private static final RowMapper<PlatformOperatorSession> MAPPER = (rs, rowNum) ->
            new PlatformOperatorSession(
                    rs.getObject("id", UUID.class),
                    rs.getObject("operator_id", UUID.class),
                    rs.getString("token_hash"),
                    rs.getBoolean("mfa_satisfied"),
                    rs.getString("ip"),
                    rs.getString("user_agent"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("last_seen_at", OffsetDateTime.class),
                    rs.getObject("idle_expires_at", OffsetDateTime.class),
                    rs.getObject("expires_at", OffsetDateTime.class),
                    rs.getObject("revoked_at", OffsetDateTime.class));

    private static final String SELECT = """
            SELECT id, operator_id, token_hash, mfa_satisfied, ip, user_agent, created_at,
                   last_seen_at, idle_expires_at, expires_at, revoked_at
              FROM platform_operator_session
            """;

    private final JdbcTemplate jdbcTemplate;

    public PlatformOperatorSessionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID id, UUID operatorId, String tokenHash, boolean mfaSatisfied,
                       String ip, String userAgent, OffsetDateTime idleExpiresAt,
                       OffsetDateTime expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO platform_operator_session
                    (id, operator_id, token_hash, mfa_satisfied, ip, user_agent,
                     idle_expires_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, operatorId, tokenHash, mfaSatisfied, ip, userAgent,
                idleExpiresAt, expiresAt);
    }

    public Optional<PlatformOperatorSession> findByTokenHash(String tokenHash) {
        return jdbcTemplate.query(SELECT + " WHERE token_hash = ?", MAPPER, tokenHash)
                .stream().findFirst();
    }

    public void touch(UUID id, OffsetDateTime lastSeenAt, OffsetDateTime idleExpiresAt) {
        jdbcTemplate.update("""
                UPDATE platform_operator_session
                   SET last_seen_at = ?, idle_expires_at = ?
                 WHERE id = ?
                """, lastSeenAt, idleExpiresAt, id);
    }

    public void markMfaSatisfied(UUID id) {
        jdbcTemplate.update("""
                UPDATE platform_operator_session SET mfa_satisfied = true WHERE id = ?
                """, id);
    }

    public void revoke(UUID id, String reason) {
        jdbcTemplate.update("""
                UPDATE platform_operator_session
                   SET revoked_at = now(), revoked_reason = ?
                 WHERE id = ? AND revoked_at IS NULL
                """, reason, id);
    }
}
