package com.acme.platform.organization;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 组织范围的只读查询：供授权决策计算本人、下属、汇报链、部门子树与代管范围。
 *
 * <p>授权模块不直接读组织表；所有「当前生效」口径都集中在这里，避免同一语义出现两套 SQL。
 * 调用方必须已经处于 {@code TenantTransactions.inTenant(...)} 事务里。
 */
@Component
public class OrganizationScopeQueries {

    private final JdbcTemplate jdbcTemplate;

    public OrganizationScopeQueries(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 当前生效的雇佣：状态 active 且区间覆盖今天，多条时取最晚生效的一段。 */
    public Optional<EmploymentScope> scopeOfPerson(UUID personId, LocalDate today) {
        if (personId == null) {
            return Optional.empty();
        }
        List<UUID> employments = jdbcTemplate.query("""
                SELECT id FROM organization_employment
                 WHERE person_id = ?
                   AND status = 'active'
                   AND effective_from <= ?
                   AND (effective_to IS NULL OR effective_to > ?)
                 ORDER BY effective_from DESC
                 LIMIT 1
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), personId, today, today);
        if (employments.isEmpty()) {
            return Optional.empty();
        }
        UUID employmentId = employments.getFirst();

        UUID primaryUnitId = null;
        UUID legalEntityId = null;
        List<UUID[]> unitRefs = jdbcTemplate.query("""
                SELECT pos.org_unit_id, u.legal_entity_id
                  FROM organization_employment_assignment a
                  JOIN organization_position pos
                    ON pos.id = a.position_id AND pos.tenant_id = a.tenant_id
                  JOIN organization_unit u
                    ON u.id = pos.org_unit_id AND u.tenant_id = pos.tenant_id
                 WHERE a.employment_id = ?
                   AND a.assignment_type = 'primary'
                   AND a.effective_from <= ?
                   AND (a.effective_to IS NULL OR a.effective_to > ?)
                 ORDER BY a.effective_from DESC
                 LIMIT 1
                """, (rs, rowNum) -> new UUID[]{
                        rs.getObject("org_unit_id", UUID.class),
                        rs.getObject("legal_entity_id", UUID.class)},
                employmentId, today, today);
        if (!unitRefs.isEmpty()) {
            primaryUnitId = unitRefs.getFirst()[0];
            legalEntityId = unitRefs.getFirst()[1];
        }

        Set<UUID> directReports = new HashSet<>(jdbcTemplate.query("""
                SELECT DISTINCT e.person_id
                  FROM organization_reporting_line r
                  JOIN organization_employment e
                    ON e.id = r.subordinate_employment_id AND e.tenant_id = r.tenant_id
                 WHERE r.manager_employment_id = ?
                   AND r.line_type = 'solid'
                   AND r.effective_from <= ?
                   AND (r.effective_to IS NULL OR r.effective_to > ?)
                """, (rs, rowNum) -> rs.getObject("person_id", UUID.class),
                employmentId, today, today));

        Set<UUID> chain = new HashSet<>(jdbcTemplate.query("""
                WITH RECURSIVE chain(employment_id, depth) AS (
                    SELECT r.subordinate_employment_id, 1
                      FROM organization_reporting_line r
                     WHERE r.manager_employment_id = ?
                       AND r.line_type = 'solid'
                       AND r.effective_from <= ?
                       AND (r.effective_to IS NULL OR r.effective_to > ?)
                    UNION ALL
                    SELECT r.subordinate_employment_id, c.depth + 1
                      FROM chain c
                      JOIN organization_reporting_line r
                        ON r.manager_employment_id = c.employment_id
                       AND r.line_type = 'solid'
                       AND r.effective_from <= ?
                       AND (r.effective_to IS NULL OR r.effective_to > ?)
                     WHERE c.depth < 50
                )
                SELECT DISTINCT e.person_id
                  FROM chain c
                  JOIN organization_employment e ON e.id = c.employment_id
                """, (rs, rowNum) -> rs.getObject("person_id", UUID.class),
                employmentId, today, today, today, today));

        Set<UUID> delegatedUnits = new HashSet<>(jdbcTemplate.query("""
                SELECT scope_org_unit_id FROM organization_delegation
                 WHERE delegate_employment_id = ?
                   AND effective_from <= ?
                   AND effective_to > ?
                """, (rs, rowNum) -> rs.getObject("scope_org_unit_id", UUID.class),
                employmentId, today, today));

        List<String> ownerTypes = jdbcTemplate.query("""
                SELECT role_type FROM organization_owner_role
                 WHERE employment_id = ?
                   AND effective_from <= ?
                   AND (effective_to IS NULL OR effective_to > ?)
                """, (rs, rowNum) -> rs.getString("role_type"), employmentId, today, today);

        return Optional.of(new EmploymentScope(personId, employmentId, primaryUnitId, legalEntityId,
                ownerTypes.contains("owner"), ownerTypes.contains("trustee"),
                directReports, chain, delegatedUnits));
    }

    /** 部门自身的子树（含自身），用于「部门及子部门」范围。 */
    public Set<UUID> subtreeUnitIds(UUID rootUnitId) {
        if (rootUnitId == null) {
            return Set.of();
        }
        return new HashSet<>(jdbcTemplate.query("""
                WITH RECURSIVE tree(id, depth) AS (
                    SELECT id, 0 FROM organization_unit WHERE id = ?
                    UNION ALL
                    SELECT u.id, t.depth + 1
                      FROM organization_unit u
                      JOIN tree t ON u.parent_id = t.id
                     WHERE t.depth < 50
                )
                SELECT id FROM tree
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), rootUnitId));
    }

    /** 法人下的全部部门，用于「指定法人」范围。 */
    public Set<UUID> unitsInLegalEntity(UUID legalEntityId) {
        if (legalEntityId == null) {
            return Set.of();
        }
        return new HashSet<>(jdbcTemplate.query("""
                SELECT id FROM organization_unit WHERE legal_entity_id = ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), legalEntityId));
    }

    /** 人员的当前主岗部门，供「资源是某个人」时判断是否落在部门范围内。 */
    public Optional<UUID> primaryUnitOfPerson(UUID personId, LocalDate today) {
        if (personId == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                SELECT pos.org_unit_id
                  FROM organization_employment e
                  JOIN organization_employment_assignment a
                    ON a.employment_id = e.id AND a.tenant_id = e.tenant_id
                  JOIN organization_position pos
                    ON pos.id = a.position_id AND pos.tenant_id = a.tenant_id
                 WHERE e.person_id = ?
                   AND e.status = 'active'
                   AND a.assignment_type = 'primary'
                   AND a.effective_from <= ?
                   AND (a.effective_to IS NULL OR a.effective_to > ?)
                 ORDER BY e.effective_from DESC
                 LIMIT 1
                """, (rs, rowNum) -> rs.getObject("org_unit_id", UUID.class),
                personId, today, today).stream().findFirst();
    }

    public boolean unitExists(UUID unitId) {
        if (unitId == null) {
            return false;
        }
        Boolean found = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM organization_unit WHERE id = ?)", Boolean.class, unitId);
        return Boolean.TRUE.equals(found);
    }

    public boolean legalEntityExists(UUID legalEntityId) {
        if (legalEntityId == null) {
            return false;
        }
        Boolean found = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM organization_legal_entity WHERE id = ?)",
                Boolean.class, legalEntityId);
        return Boolean.TRUE.equals(found);
    }
}
