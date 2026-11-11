package com.acme.platform.organization;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 自然人的 JDBC 存储：只写 SQL，事务由调用方的 {@code TenantTransactions.inTenant(...)} 提供。
 */
@Component
public class PersonStore {

    private static final RowMapper<Person> MAPPER = (rs, rowNum) -> new Person(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("display_name"),
            rs.getString("primary_email"),
            PersonStatus.fromWire(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public PersonStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Person> find(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, display_name, primary_email, status, created_at
                  FROM organization_person
                 WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public List<Person> list(int limit) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, display_name, primary_email, status, created_at
                  FROM organization_person
                 ORDER BY created_at DESC
                 LIMIT ?
                """, MAPPER, limit);
    }

    public boolean exists(UUID id) {
        Boolean found = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM organization_person WHERE id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(found);
    }

    public void insert(UUID id, String displayName, String primaryEmail) {
        jdbcTemplate.update("""
                INSERT INTO organization_person (id, tenant_id, display_name, primary_email)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
                """, id, displayName, primaryEmail);
    }
}
