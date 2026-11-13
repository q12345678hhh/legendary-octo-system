package com.acme.platform.identity.operator;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 平台运营账号的 JDBC 存储。表在平台域，不受租户 RLS 约束。 */
@Component
public class PlatformOperatorStore {

    private static final RowMapper<PlatformOperator> MAPPER = (rs, rowNum) -> new PlatformOperator(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getString("role_code"),
            rs.getString("password_hash"),
            rs.getString("mfa_secret_cipher"),
            rs.getString("mfa_nonce"),
            rs.getBoolean("mfa_confirmed"),
            rs.getString("status"),
            rs.getInt("failed_attempts"),
            rs.getObject("locked_until", OffsetDateTime.class),
            rs.getObject("last_login_at", OffsetDateTime.class));

    private static final String SELECT = """
            SELECT id, email, display_name, role_code, password_hash, mfa_secret_cipher, mfa_nonce,
                   mfa_confirmed, status, failed_attempts, locked_until, last_login_at
              FROM platform_operator
            """;

    private final JdbcTemplate jdbcTemplate;

    public PlatformOperatorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PlatformOperator> findByEmail(String email) {
        return jdbcTemplate.query(SELECT + " WHERE email = ?", MAPPER, email)
                .stream().findFirst();
    }

    public Optional<PlatformOperator> findById(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM platform_operator", Long.class);
        return count == null ? 0 : count;
    }

    public PlatformOperator insert(String email, String displayName, String roleCode,
                                   String passwordHash) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform_operator (id, email, display_name, role_code, password_hash)
                VALUES (?, ?, ?, ?, ?)
                """, id, email, displayName, roleCode, passwordHash);
        return findById(id).orElseThrow();
    }

    public void recordSuccess(UUID id, OffsetDateTime now) {
        jdbcTemplate.update("""
                UPDATE platform_operator
                   SET failed_attempts = 0, locked_until = NULL, last_login_at = ?,
                       updated_at = ?
                 WHERE id = ?
                """, now, now, id);
    }

    public void recordFailure(UUID id, int failedAttempts, OffsetDateTime lockedUntil,
                              OffsetDateTime now) {
        jdbcTemplate.update("""
                UPDATE platform_operator
                   SET failed_attempts = ?, locked_until = ?, updated_at = ?
                 WHERE id = ?
                """, failedAttempts, lockedUntil, now, id);
    }

    public void bindMfa(UUID id, String cipher, String nonce, boolean confirmed,
                        OffsetDateTime now) {
        jdbcTemplate.update("""
                UPDATE platform_operator
                   SET mfa_secret_cipher = ?, mfa_nonce = ?, mfa_confirmed = ?, updated_at = ?
                 WHERE id = ?
                """, cipher, nonce, confirmed, now, id);
    }

    public void confirmMfa(UUID id) {
        jdbcTemplate.update("""
                UPDATE platform_operator SET mfa_confirmed = true, updated_at = now()
                 WHERE id = ? AND mfa_secret_cipher IS NOT NULL
                """, id);
    }

    public List<PlatformOperator> list(int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query(SELECT + " ORDER BY created_at DESC LIMIT ?", MAPPER, capped);
    }
}
