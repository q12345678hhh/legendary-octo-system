package com.acme.platform.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** OIDC/SAML/SCIM 预留模型的 JDBC 存储；config 以 jsonb 保存。 */
@Component
public class IdentityProviderStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdentityProviderStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<IdentityProvider> mapper = (rs, rowNum) -> new IdentityProvider(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("provider_type"),
            rs.getString("name"),
            rs.getString("status"),
            readConfig(rs.getString("config")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private static final String SELECT = """
            SELECT id, tenant_id, provider_type, name, status, config, created_at, updated_at
              FROM identity_identity_provider
            """;

    private Map<String, Object> readConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, MAP_TYPE);
    }

    public void insert(UUID id, String providerType, String name, Map<String, Object> config,
                       String status, String createdBy) {
        jdbcTemplate.update("""
                INSERT INTO identity_identity_provider
                    (id, tenant_id, provider_type, name, config, status, created_by)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?::jsonb, ?, ?)
                """, id, providerType, name, objectMapper.writeValueAsString(config), status, createdBy);
    }

    public Optional<IdentityProvider> find(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", mapper, id).stream().findFirst();
    }

    public List<IdentityProvider> list(int limit) {
        return jdbcTemplate.query(SELECT + " ORDER BY created_at DESC LIMIT ?", mapper, limit);
    }

    public void update(UUID id, Map<String, Object> config, String status) {
        jdbcTemplate.update("""
                UPDATE identity_identity_provider
                   SET config = ?::jsonb, status = ?, updated_at = now()
                 WHERE id = ?
                """, objectMapper.writeValueAsString(config), status, id);
    }
}
