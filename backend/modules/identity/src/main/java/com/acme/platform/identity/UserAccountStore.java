package com.acme.platform.identity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 登录账号的 JDBC 存储；密码哈希只在需要校验时读取。 */
@Component
public class UserAccountStore {

    private static final RowMapper<UserAccount> MAPPER = (rs, rowNum) -> new UserAccount(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("email"),
            rs.getString("display_name"),
            UserStatus.fromWire(rs.getString("status")),
            rs.getInt("auth_version"),
            rs.getBoolean("mfa_required"),
            rs.getInt("failed_attempts"),
            rs.getObject("locked_until", OffsetDateTime.class),
            rs.getObject("activated_at", OffsetDateTime.class),
            rs.getObject("last_login_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public UserAccountStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record AccountWithSecret(UserAccount account, String passwordHash) {
    }

    public Optional<AccountWithSecret> findByEmail(String normalizedEmail) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, email, display_name, status, auth_version, mfa_required,
                       failed_attempts, locked_until, activated_at, last_login_at, password_hash
                  FROM identity_user_account
                 WHERE email_normalized = ?
                """, (rs, rowNum) -> new AccountWithSecret(MAPPER.mapRow(rs, rowNum),
                        rs.getString("password_hash")), normalizedEmail).stream().findFirst();
    }

    public Optional<AccountWithSecret> findById(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, email, display_name, status, auth_version, mfa_required,
                       failed_attempts, locked_until, activated_at, last_login_at, password_hash
                  FROM identity_user_account
                 WHERE id = ?
                """, (rs, rowNum) -> new AccountWithSecret(MAPPER.mapRow(rs, rowNum),
                        rs.getString("password_hash")), id).stream().findFirst();
    }

    public Optional<UserAccount> findAccount(UUID id) {
        return findById(id).map(AccountWithSecret::account);
    }

    public Optional<UserAccount> findByEmailOnly(String normalizedEmail) {
        return findByEmail(normalizedEmail).map(AccountWithSecret::account);
    }

    public void insertInvited(UUID id, String email, String normalizedEmail, String displayName) {
        jdbcTemplate.update("""
                INSERT INTO identity_user_account
                    (id, tenant_id, email, email_normalized, display_name, status)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, 'invited')
                """, id, email, normalizedEmail, displayName);
    }

    public void activate(UUID id, String passwordHash) {
        jdbcTemplate.update("""
                UPDATE identity_user_account
                   SET password_hash = ?, status = 'active', activated_at = now(),
                       failed_attempts = 0, locked_until = NULL,
                       auth_version = auth_version + 1, updated_at = now()
                 WHERE id = ?
                """, passwordHash, id);
    }

    public void updatePassword(UUID id, String passwordHash) {
        jdbcTemplate.update("""
                UPDATE identity_user_account
                   SET password_hash = ?, auth_version = auth_version + 1,
                       failed_attempts = 0, locked_until = NULL, updated_at = now()
                 WHERE id = ?
                """, passwordHash, id);
    }

    public void recordFailure(UUID id, int maxAttempts, Duration lockDuration) {
        jdbcTemplate.update("""
                UPDATE identity_user_account
                   SET failed_attempts = failed_attempts + 1,
                       locked_until = CASE
                           WHEN failed_attempts + 1 >= ? THEN now() + make_interval(secs => ?)
                           ELSE locked_until
                       END,
                       updated_at = now()
                 WHERE id = ?
                """, maxAttempts, lockDuration.toSeconds(), id);
    }

    public void clearFailuresAndMarkLogin(UUID id) {
        jdbcTemplate.update("""
                UPDATE identity_user_account
                   SET failed_attempts = 0, locked_until = NULL, last_login_at = now(), updated_at = now()
                 WHERE id = ?
                """, id);
    }

    public void setStatus(UUID id, UserStatus status) {
        jdbcTemplate.update("""
                UPDATE identity_user_account
                   SET status = ?,
                       disabled_at = CASE WHEN ? = 'disabled' THEN now() ELSE disabled_at END,
                       auth_version = auth_version + 1,
                       updated_at = now()
                 WHERE id = ?
                """, status.wire(), status.wire(), id);
    }

    public void bumpAuthVersion(UUID id) {
        jdbcTemplate.update(
                "UPDATE identity_user_account SET auth_version = auth_version + 1, updated_at = now() WHERE id = ?",
                id);
    }

    public void setMfaRequired(UUID id, boolean required) {
        jdbcTemplate.update(
                "UPDATE identity_user_account SET mfa_required = ?, updated_at = now() WHERE id = ?",
                required, id);
    }

    public Optional<Integer> authVersion(UUID id) {
        return jdbcTemplate.queryForList(
                "SELECT auth_version FROM identity_user_account WHERE id = ?", Integer.class, id)
                .stream().findFirst();
    }

    public void insertPasswordHistory(UUID userId, String passwordHash) {
        jdbcTemplate.update("""
                INSERT INTO identity_password_history
                    (tenant_id, user_id, password_hash)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
                """, userId, passwordHash);
    }

    public java.util.List<String> recentPasswordHashes(UUID userId, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT password_hash FROM identity_password_history
                 WHERE user_id = ? ORDER BY created_at DESC LIMIT ?
                """, String.class, userId, limit);
    }
}
