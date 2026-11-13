package com.acme.platform.department;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 部门空间的 JDBC 存储。
 *
 * <p>调用方必须已经处于 {@code TenantTransactions.inTenant(...)} 事务里；
 * 空间不存在时返回空，由应用服务区分 404 与无权限。
 */
@Component
public class DepartmentSpaceStore {

    private final JdbcTemplate jdbcTemplate;

    public DepartmentSpaceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DepartmentSpace> findByUnit(UUID orgUnitId) {
        return jdbcTemplate.query("""
                        SELECT id, org_unit_id, name, status, created_at, archived_at
                          FROM department_space
                         WHERE org_unit_id = ?
                        """, (rs, rowNum) -> new DepartmentSpace(
                        rs.getObject("id", UUID.class),
                        rs.getObject("org_unit_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class),
                        rs.getObject("archived_at", java.time.OffsetDateTime.class)),
                orgUnitId).stream().findFirst();
    }

    /** 租户范围或指定部门集合的可见空间；集合为空时不返回任何行。 */
    public List<DepartmentSpace> list(boolean tenantWide, Set<UUID> orgUnitIds) {
        if (tenantWide) {
            return jdbcTemplate.query("""
                    SELECT id, org_unit_id, name, status, created_at, archived_at
                      FROM department_space
                     WHERE status = 'active'
                     ORDER BY name
                    """, DepartmentSpaceStore::map);
        }
        if (orgUnitIds == null || orgUnitIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(orgUnitIds.size(), "?"));
        return jdbcTemplate.query("""
                SELECT id, org_unit_id, name, status, created_at, archived_at
                  FROM department_space
                 WHERE status = 'active'
                   AND org_unit_id IN (%s)
                 ORDER BY name
                """.formatted(placeholders), DepartmentSpaceStore::map, orgUnitIds.toArray());
    }

    /** 部门首屏成员投影：主/兼岗、职位、实线主管与汇报层级。 */
    public List<MemberRow> members(UUID orgUnitId, LocalDate today) {
        return jdbcTemplate.query("""
                WITH RECURSIVE ancestry(employment_id, depth) AS (
                    SELECT r.subordinate_employment_id, 1
                      FROM organization_reporting_line r
                     WHERE r.line_type = 'solid'
                       AND r.effective_from <= ?
                       AND (r.effective_to IS NULL OR r.effective_to > ?)
                    UNION ALL
                    SELECT r.manager_employment_id, a.depth + 1
                      FROM ancestry a
                      JOIN organization_reporting_line r
                        ON r.subordinate_employment_id = a.employment_id
                       AND r.line_type = 'solid'
                       AND r.effective_from <= ?
                       AND (r.effective_to IS NULL OR r.effective_to > ?)
                     WHERE a.depth < 20
                ), depth AS (
                    SELECT employment_id, max(depth) AS depth
                      FROM ancestry
                     GROUP BY employment_id
                )
                SELECT e.id           AS employment_id,
                       e.person_id    AS person_id,
                       p.display_name AS display_name,
                       pos.name       AS position_name,
                       job.name       AS job_name,
                       a.assignment_type AS assignment_type,
                       mgr.display_name  AS manager_name,
                       coalesce(d.depth, 0) AS depth
                  FROM organization_employment e
                  JOIN organization_person p
                    ON p.id = e.person_id AND p.tenant_id = e.tenant_id
                  JOIN organization_employment_assignment a
                    ON a.employment_id = e.id AND a.tenant_id = e.tenant_id
                  JOIN organization_position pos
                    ON pos.id = a.position_id AND pos.tenant_id = a.tenant_id
                  JOIN organization_job job
                    ON job.id = pos.job_id AND job.tenant_id = pos.tenant_id
                  LEFT JOIN organization_reporting_line r
                    ON r.subordinate_employment_id = e.id
                   AND r.tenant_id = e.tenant_id
                   AND r.line_type = 'solid'
                   AND r.effective_from <= ?
                   AND (r.effective_to IS NULL OR r.effective_to > ?)
                  LEFT JOIN organization_employment me
                    ON me.id = r.manager_employment_id AND me.tenant_id = r.tenant_id
                  LEFT JOIN organization_person mgr
                    ON mgr.id = me.person_id AND mgr.tenant_id = me.tenant_id
                  LEFT JOIN depth d ON d.employment_id = e.id
                 WHERE pos.org_unit_id = ?
                   AND a.effective_from <= ?
                   AND (a.effective_to IS NULL OR a.effective_to > ?)
                   AND e.status = 'active'
                   AND e.effective_from <= ?
                   AND (e.effective_to IS NULL OR e.effective_to > ?)
                 ORDER BY a.assignment_type, pos.name, p.display_name
                """, (rs, rowNum) -> new MemberRow(
                        rs.getObject("employment_id", UUID.class),
                        rs.getObject("person_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("position_name"),
                        rs.getString("job_name"),
                        rs.getString("assignment_type"),
                        rs.getString("manager_name"),
                        rs.getInt("depth")),
                today, today, today, today, today, today, orgUnitId,
                today, today, today, today);
    }

    /** 部门设置；未显式配置时返回默认值。 */
    public Setting setting(UUID orgUnitId) {
        return jdbcTemplate.query("""
                        SELECT day_end_time, week_report_due_weekday
                          FROM department_space_setting
                         WHERE org_unit_id = ?
                        """, (rs, rowNum) -> new Setting(
                        rs.getTime("day_end_time").toLocalTime(),
                        rs.getInt("week_report_due_weekday")),
                orgUnitId).stream().findFirst()
                .orElse(new Setting(java.time.LocalTime.of(18, 0), 5));
    }

    public void upsertSetting(UUID orgUnitId, java.time.LocalTime dayEndTime, int weekday,
                              String updatedBy) {
        jdbcTemplate.update("""
                INSERT INTO department_space_setting
                    (tenant_id, org_unit_id, day_end_time, week_report_due_weekday, updated_by)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, org_unit_id) DO UPDATE
                   SET day_end_time = EXCLUDED.day_end_time,
                       week_report_due_weekday = EXCLUDED.week_report_due_weekday,
                       updated_by = EXCLUDED.updated_by,
                       updated_at = now()
                """, orgUnitId, dayEndTime, weekday, updatedBy);
    }

    private static DepartmentSpace map(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new DepartmentSpace(
                rs.getObject("id", UUID.class),
                rs.getObject("org_unit_id", UUID.class),
                rs.getString("name"),
                rs.getString("status"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("archived_at", java.time.OffsetDateTime.class));
    }

    public record MemberRow(UUID employmentId,
                            UUID personId,
                            String displayName,
                            String positionName,
                            String jobName,
                            String assignmentType,
                            String managerName,
                            int depth) {
    }

    public record Setting(java.time.LocalTime dayEndTime, int weekReportDueWeekday) {
    }
}
