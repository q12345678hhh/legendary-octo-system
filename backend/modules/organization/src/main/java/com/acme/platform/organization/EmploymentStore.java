package com.acme.platform.organization;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 雇佣关系的 JDBC 存储；事务边界由调用方控制。 */
@Component
public class EmploymentStore {

    private static final RowMapper<Employment> MAPPER = (rs, rowNum) -> new Employment(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("person_id", UUID.class),
            rs.getString("employment_no"),
            EmploymentStatus.fromWire(rs.getString("status")),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getString("job_title"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public EmploymentStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Employment> find(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, person_id, employment_no, status,
                       effective_from, effective_to, job_title, created_at
                  FROM organization_employment
                 WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public List<Employment> listByPerson(UUID personId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, person_id, employment_no, status,
                       effective_from, effective_to, job_title, created_at
                  FROM organization_employment
                 WHERE person_id = ?
                 ORDER BY effective_from DESC, created_at DESC
                """, MAPPER, personId);
    }

    public void insert(UUID id, UUID personId, String employmentNo, LocalDate effectiveFrom, String jobTitle) {
        jdbcTemplate.update("""
                INSERT INTO organization_employment
                    (id, tenant_id, person_id, employment_no, effective_from, job_title)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
                """, id, personId, employmentNo, effectiveFrom, jobTitle);
    }

    public int end(UUID id, LocalDate effectiveTo) {
        return jdbcTemplate.update("""
                UPDATE organization_employment
                   SET status = 'ended', effective_to = ?, updated_at = now()
                 WHERE id = ? AND status = 'active'
                """, effectiveTo, id);
    }
}
