package com.acme.platform.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 服务账号的 JDBC 存储。 */
@Component
public class ServiceAccountStore {

    private static final RowMapper<ServiceAccount> MAPPER = (rs, rowNum) -> new ServiceAccount(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("actor_id", UUID.class),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final String SELECT = """
            SELECT id, tenant_id, actor_id, name, description, status, created_at
              FROM identity_service_account
            """;

    private final JdbcTemplate jdbcTemplate;

    public ServiceAccountStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID id, UUID actorId, String name, String description, String createdBy) {
        jdbcTemplate.update("""
                INSERT INTO identity_service_account
                    (id, tenant_id, actor_id, name, description, created_by)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
                """, id, actorId, name, description, createdBy);
    }

    public Optional<ServiceAccount> find(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<ServiceAccount> findByActor(UUID actorId) {
        return jdbcTemplate.query(SELECT + " WHERE actor_id = ?", MAPPER, actorId).stream().findFirst();
    }

    public List<ServiceAccount> list(int limit) {
        return jdbcTemplate.query(SELECT + " ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }

    public void disable(UUID id) {
        jdbcTemplate.update("""
                UPDATE identity_service_account SET status = 'disabled', updated_at = now() WHERE id = ?
                """, id);
    }
}
