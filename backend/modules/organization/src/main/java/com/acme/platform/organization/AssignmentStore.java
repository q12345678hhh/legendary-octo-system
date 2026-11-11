package com.acme.platform.organization;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 主/兼岗存储。全部方法都必须在租户事务内调用。 */
@Component
public class AssignmentStore {

    private static final RowMapper<EmploymentAssignment> MAPPER = (rs, rowNum) -> new EmploymentAssignment(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("employment_id", UUID.class),
            rs.getObject("position_id", UUID.class),
            EmploymentAssignment.AssignmentType.fromWire(rs.getString("assignment_type")),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public AssignmentStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<EmploymentAssignment> find(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, employment_id, position_id, assignment_type,
                       effective_from, effective_to, created_at
                  FROM organization_employment_assignment WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public List<EmploymentAssignment> listByEmployment(UUID employmentId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, employment_id, position_id, assignment_type,
                       effective_from, effective_to, created_at
                  FROM organization_employment_assignment
                 WHERE employment_id = ?
                 ORDER BY effective_from DESC, created_at DESC
                """, MAPPER, employmentId);
    }

    public List<EmploymentAssignment> listCurrentByEmployment(UUID employmentId, LocalDate today) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, employment_id, position_id, assignment_type,
                       effective_from, effective_to, created_at
                  FROM organization_employment_assignment
                 WHERE employment_id = ?
                   AND effective_from <= ?
                   AND (effective_to IS NULL OR effective_to > ?)
                 ORDER BY assignment_type, effective_from
                """, MAPPER, employmentId, today, today);
    }

    public Optional<EmploymentAssignment> findOpenPrimary(UUID employmentId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, employment_id, position_id, assignment_type,
                       effective_from, effective_to, created_at
                  FROM organization_employment_assignment
                 WHERE employment_id = ? AND assignment_type = 'primary' AND effective_to IS NULL
                """, MAPPER, employmentId).stream().findFirst();
    }

    /** 主岗在任一时刻只能有一条；兼岗允许与主岗并存，因此只按主岗判断重叠。 */
    public boolean hasOverlappingPrimary(UUID employmentId, LocalDate from, LocalDate to) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM organization_employment_assignment
                 WHERE employment_id = ?
                   AND assignment_type = 'primary'
                   AND effective_from < coalesce(?, DATE '9999-12-31')
                   AND (effective_to IS NULL OR effective_to > ?)
                """, Long.class, employmentId, to, from);
        return count != null && count > 0;
    }

    public void insert(UUID id, UUID employmentId, UUID positionId,
                       EmploymentAssignment.AssignmentType type, LocalDate from, LocalDate to) {
        jdbcTemplate.update("""
                INSERT INTO organization_employment_assignment
                    (id, tenant_id, employment_id, position_id, assignment_type,
                     effective_from, effective_to)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
                """, id, employmentId, positionId, type.wire(), from, to);
    }

    public int closePrimary(UUID id, LocalDate effectiveTo) {
        return jdbcTemplate.update("""
                UPDATE organization_employment_assignment
                   SET effective_to = ?, updated_at = now()
                 WHERE id = ? AND effective_to IS NULL
                """, effectiveTo, id);
    }

    public int end(UUID id, LocalDate effectiveTo) {
        return jdbcTemplate.update("""
                UPDATE organization_employment_assignment
                   SET effective_to = ?, updated_at = now()
                 WHERE id = ? AND effective_to IS NULL
                """, effectiveTo, id);
    }
}
