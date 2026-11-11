package com.acme.platform.organization;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 汇报线存储。 */
@Component
public class ReportingLineStore {

    private static final RowMapper<ReportingLine> MAPPER = (rs, rowNum) -> new ReportingLine(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("subordinate_employment_id", UUID.class),
            rs.getObject("manager_employment_id", UUID.class),
            ReportingLine.LineType.fromWire(rs.getString("line_type")),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getString("created_by"),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public ReportingLineStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ReportingLine> find(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, subordinate_employment_id, manager_employment_id,
                       line_type, effective_from, effective_to, created_by, created_at
                  FROM organization_reporting_line WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public List<ReportingLine> listCurrent(LocalDate today) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, subordinate_employment_id, manager_employment_id,
                       line_type, effective_from, effective_to, created_by, created_at
                  FROM organization_reporting_line
                 WHERE effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                 ORDER BY line_type, created_at
                """, MAPPER, today, today);
    }

    public List<UUID> managersOf(UUID employmentId, LocalDate today) {
        return jdbcTemplate.query("""
                SELECT manager_employment_id FROM organization_reporting_line
                 WHERE subordinate_employment_id = ?
                   AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                """, (rs, rowNum) -> rs.getObject("manager_employment_id", UUID.class),
                employmentId, today, today);
    }

    public List<ReportingLine> listBySubordinate(UUID employmentId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, subordinate_employment_id, manager_employment_id,
                       line_type, effective_from, effective_to, created_by, created_at
                  FROM organization_reporting_line
                 WHERE subordinate_employment_id = ?
                 ORDER BY effective_from DESC
                """, MAPPER, employmentId);
    }

    public boolean hasOpenSolid(UUID subordinateEmploymentId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM organization_reporting_line
                 WHERE subordinate_employment_id = ? AND line_type = 'solid' AND effective_to IS NULL
                """, Long.class, subordinateEmploymentId);
        return count != null && count > 0;
    }

    public void insert(UUID id, UUID subordinateEmploymentId, UUID managerEmploymentId,
                       ReportingLine.LineType lineType, LocalDate from, LocalDate to, String actor) {
        jdbcTemplate.update("""
                INSERT INTO organization_reporting_line
                    (id, tenant_id, subordinate_employment_id, manager_employment_id,
                     line_type, effective_from, effective_to, created_by)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
                """, id, subordinateEmploymentId, managerEmploymentId, lineType.wire(), from, to, actor);
    }

    public int end(UUID id, LocalDate effectiveTo) {
        return jdbcTemplate.update("""
                UPDATE organization_reporting_line
                   SET effective_to = ?, updated_at = now()
                 WHERE id = ? AND effective_to IS NULL
                """, effectiveTo, id);
    }
}
