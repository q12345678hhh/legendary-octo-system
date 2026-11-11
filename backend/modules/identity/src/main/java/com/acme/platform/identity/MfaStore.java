package com.acme.platform.identity;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** MFA 方法、恢复码与登录挑战的 JDBC 存储。 */
@Component
public class MfaStore {

    private static final RowMapper<MfaMethod> METHOD_MAPPER = (rs, rowNum) -> new MfaMethod(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("secret_ciphertext"),
            rs.getString("secret_nonce"),
            MfaStatus.fromWire(rs.getString("status")),
            rs.getObject("confirmed_at", OffsetDateTime.class),
            rs.getObject("last_used_step", Long.class));

    private static final String SELECT_METHOD = """
            SELECT id, tenant_id, user_id, secret_ciphertext, secret_nonce, status,
                   confirmed_at, last_used_step
              FROM identity_mfa_method
            """;

    private final JdbcTemplate jdbcTemplate;

    public MfaStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<MfaMethod> findActive(UUID userId) {
        return jdbcTemplate.query(SELECT_METHOD + " WHERE user_id = ? AND status = 'active'",
                METHOD_MAPPER, userId).stream().findFirst();
    }

    public Optional<MfaMethod> findPending(UUID userId) {
        return jdbcTemplate.query(SELECT_METHOD + " WHERE user_id = ? AND status = 'pending'",
                METHOD_MAPPER, userId).stream().findFirst();
    }

    public void disablePending(UUID userId) {
        jdbcTemplate.update("""
                UPDATE identity_mfa_method
                   SET status = 'disabled', updated_at = now()
                 WHERE user_id = ? AND status = 'pending'
                """, userId);
    }

    public void insertPending(UUID id, UUID userId, String ciphertext, String nonce) {
        jdbcTemplate.update("""
                INSERT INTO identity_mfa_method
                    (id, tenant_id, user_id, secret_ciphertext, secret_nonce, status)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, 'pending')
                """, id, userId, ciphertext, nonce);
    }

    public void activate(UUID id) {
        jdbcTemplate.update("""
                UPDATE identity_mfa_method
                   SET status = 'active', confirmed_at = now(), updated_at = now()
                 WHERE id = ?
                """, id);
    }

    public void disableAll(UUID userId) {
        jdbcTemplate.update("""
                UPDATE identity_mfa_method
                   SET status = 'disabled', updated_at = now()
                 WHERE user_id = ? AND status <> 'disabled'
                """, userId);
    }

    public void updateLastStep(UUID id, long step) {
        jdbcTemplate.update("""
                UPDATE identity_mfa_method SET last_used_step = ?, updated_at = now() WHERE id = ?
                """, step, id);
    }

    public void deleteRecoveryCodes(UUID userId) {
        jdbcTemplate.update("DELETE FROM identity_recovery_code WHERE user_id = ?", userId);
    }

    public void insertRecoveryCode(UUID userId, String codeHash) {
        jdbcTemplate.update("""
                INSERT INTO identity_recovery_code
                    (tenant_id, user_id, code_hash)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
                """, userId, codeHash);
    }

    public int consumeRecoveryCode(UUID userId, String codeHash) {
        return jdbcTemplate.update("""
                UPDATE identity_recovery_code SET used_at = now()
                 WHERE user_id = ? AND code_hash = ? AND used_at IS NULL
                """, userId, codeHash);
    }

    public int remainingRecoveryCodes(UUID userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM identity_recovery_code WHERE user_id = ? AND used_at IS NULL
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    public record Challenge(UUID id, UUID userId, OffsetDateTime expiresAt,
                            int attempts, OffsetDateTime consumedAt) {
    }

    public void insertChallenge(UUID id, UUID userId, String tokenHash, OffsetDateTime expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO identity_mfa_challenge
                    (id, tenant_id, user_id, token_hash, expires_at)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                """, id, userId, tokenHash, expiresAt);
    }

    public Optional<Challenge> findChallenge(String tokenHash) {
        return jdbcTemplate.query("""
                SELECT id, user_id, expires_at, attempts, consumed_at
                  FROM identity_mfa_challenge WHERE token_hash = ?
                """, (rs, rowNum) -> new Challenge(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getInt("attempts"),
                        rs.getObject("consumed_at", OffsetDateTime.class)),
                tokenHash).stream().findFirst();
    }

    public int incrementAttempts(UUID id) {
        jdbcTemplate.update("""
                UPDATE identity_mfa_challenge SET attempts = attempts + 1 WHERE id = ?
                """, id);
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT attempts FROM identity_mfa_challenge WHERE id = ?", Integer.class, id);
        return attempts == null ? 0 : attempts;
    }

    public void consumeChallenge(UUID id) {
        jdbcTemplate.update("""
                UPDATE identity_mfa_challenge SET consumed_at = now() WHERE id = ?
                """, id);
    }
}
