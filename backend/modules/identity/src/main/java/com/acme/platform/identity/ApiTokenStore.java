package com.acme.platform.identity;

import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** API 令牌的 JDBC 存储；scopes 使用 PostgreSQL text[]。 */
@Component
public class ApiTokenStore {

    private static final RowMapper<ApiToken> MAPPER = (rs, rowNum) -> new ApiToken(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("service_account_id", UUID.class),
            rs.getObject("actor_id", UUID.class),
            rs.getString("name"),
            rs.getString("prefix"),
            readScopes(rs.getArray("scopes")),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("revoked_at", OffsetDateTime.class),
            rs.getObject("last_used_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private static List<String> readScopes(Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            Object raw = array.getArray();
            if (raw instanceof String[] values) {
                return List.of(values);
            }
            Object[] values = (Object[]) raw;
            return Arrays.stream(values).map(String::valueOf).toList();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("读取令牌 scopes 失败", e);
        }
    }

    private static final String SELECT = """
            SELECT id, tenant_id, service_account_id, actor_id, name, prefix, scopes,
                   expires_at, revoked_at, last_used_at, created_at
              FROM identity_api_token
            """;

    private final JdbcTemplate jdbcTemplate;

    public ApiTokenStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID id, UUID serviceAccountId, UUID actorId, String name, String tokenHash,
                       String prefix, List<String> scopes, OffsetDateTime expiresAt, String createdBy) {
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO identity_api_token
                        (id, tenant_id, service_account_id, actor_id, name, token_hash, prefix,
                         scopes, expires_at, created_by)
                    VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setObject(1, id);
            ps.setObject(2, serviceAccountId);
            ps.setObject(3, actorId);
            ps.setString(4, name);
            ps.setString(5, tokenHash);
            ps.setString(6, prefix);
            ps.setArray(7, connection.createArrayOf("text", scopes.toArray()));
            ps.setObject(8, expiresAt);
            ps.setString(9, createdBy);
            return ps;
        });
    }

    public Optional<ApiToken> findByTokenHash(String tokenHash) {
        return jdbcTemplate.query(SELECT + " WHERE token_hash = ?", MAPPER, tokenHash)
                .stream().findFirst();
    }

    public List<ApiToken> listByAccount(UUID serviceAccountId) {
        return jdbcTemplate.query(SELECT + """
                 WHERE service_account_id = ? ORDER BY created_at DESC
                """, MAPPER, serviceAccountId);
    }

    public Optional<ApiToken> find(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public void revoke(UUID id, String revokedBy) {
        jdbcTemplate.update("""
                UPDATE identity_api_token SET revoked_at = now(), revoked_by = ?
                 WHERE id = ? AND revoked_at IS NULL
                """, revokedBy, id);
    }

    public void revokeAllForAccount(UUID serviceAccountId, String revokedBy) {
        jdbcTemplate.update("""
                UPDATE identity_api_token SET revoked_at = now(), revoked_by = ?
                 WHERE service_account_id = ? AND revoked_at IS NULL
                """, revokedBy, serviceAccountId);
    }

    public void touch(UUID id) {
        jdbcTemplate.update("""
                UPDATE identity_api_token SET last_used_at = now()
                 WHERE id = ? AND (last_used_at IS NULL OR last_used_at < now() - interval '60 seconds')
                """, id);
    }
}
