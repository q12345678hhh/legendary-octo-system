package com.acme.platform.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 统一主体的 JDBC 存储。 */
@Component
public class ActorStore {

    private static final RowMapper<Actor> MAPPER = (rs, rowNum) -> new Actor(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            ActorType.fromWire(rs.getString("actor_type")),
            rs.getString("display_name"),
            rs.getString("status"),
            rs.getObject("user_id", UUID.class));

    private final JdbcTemplate jdbcTemplate;

    public ActorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID id, ActorType type, String displayName, UUID userId) {
        jdbcTemplate.update("""
                INSERT INTO identity_actor (id, tenant_id, actor_type, display_name, user_id)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                """, id, type.wire(), displayName, userId);
    }

    public Optional<Actor> findById(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, actor_type, display_name, status, user_id
                  FROM identity_actor WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public Optional<Actor> findByUserId(UUID userId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, actor_type, display_name, status, user_id
                  FROM identity_actor WHERE user_id = ?
                """, MAPPER, userId).stream().findFirst();
    }

    public void disable(UUID id) {
        jdbcTemplate.update("""
                UPDATE identity_actor SET status = 'disabled', updated_at = now() WHERE id = ?
                """, id);
    }

    public void enable(UUID id) {
        jdbcTemplate.update("""
                UPDATE identity_actor SET status = 'active', updated_at = now() WHERE id = ?
                """, id);
    }
}
