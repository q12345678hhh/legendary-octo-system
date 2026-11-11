package com.acme.platform.organization;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 组织结构主数据的 JDBC 存储：法人、部门、职级、岗位、职位、成本中心、地点。
 *
 * <p>事务边界由调用方控制，所有方法都必须在 {@code TenantTransactions.inTenant(...)} 内执行；
 * 写入租户列一律取当前事务的 {@code app.tenant_id}，不信任请求体里的租户 ID。
 */
@Component
public class OrganizationStructureStore {

    private static final RowMapper<LegalEntity> LEGAL_ENTITY_MAPPER = (rs, rowNum) -> new LegalEntity(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("registration_no"),
            MasterDataStatus.fromWire(rs.getString("status")),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<OrgUnit> UNIT_MAPPER = (rs, rowNum) -> new OrgUnit(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("parent_id", UUID.class),
            rs.getObject("legal_entity_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            OrgUnit.UnitType.fromWire(rs.getString("unit_type")),
            OrgUnit.UnitStatus.fromWire(rs.getString("status")),
            rs.getInt("sort_order"),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<JobLevel> JOB_LEVEL_MAPPER = (rs, rowNum) -> new JobLevel(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("rank"),
            MasterDataStatus.fromWire(rs.getString("status")),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<Job> JOB_MAPPER = (rs, rowNum) -> new Job(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("job_family"),
            rs.getObject("default_level_id", UUID.class),
            MasterDataStatus.fromWire(rs.getString("status")),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<Position> POSITION_MAPPER = (rs, rowNum) -> new Position(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("org_unit_id", UUID.class),
            rs.getObject("job_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getObject("headcount_limit", Integer.class),
            MasterDataStatus.fromWire(rs.getString("status")),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<CostCenter> COST_CENTER_MAPPER = (rs, rowNum) -> new CostCenter(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getObject("legal_entity_id", UUID.class),
            MasterDataStatus.fromWire(rs.getString("status")),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<Location> LOCATION_MAPPER = (rs, rowNum) -> new Location(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("address"),
            rs.getString("timezone"),
            MasterDataStatus.fromWire(rs.getString("status")),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public OrganizationStructureStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------------------------------------------------------- 法人

    public Optional<LegalEntity> findLegalEntity(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, code, name, registration_no, status, created_at
                  FROM organization_legal_entity WHERE id = ?
                """, LEGAL_ENTITY_MAPPER, id).stream().findFirst();
    }

    public List<LegalEntity> listLegalEntities() {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, code, name, registration_no, status, created_at
                  FROM organization_legal_entity ORDER BY code
                """, LEGAL_ENTITY_MAPPER);
    }

    public void insertLegalEntity(UUID id, String code, String name, String registrationNo) {
        jdbcTemplate.update("""
                INSERT INTO organization_legal_entity
                    (id, tenant_id, code, name, registration_no)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                """, id, code, name, registrationNo);
    }

    public int updateLegalEntity(UUID id, String code, String name, String registrationNo,
                                 MasterDataStatus status) {
        return jdbcTemplate.update("""
                UPDATE organization_legal_entity
                   SET code = ?, name = ?, registration_no = ?, status = ?, updated_at = now()
                 WHERE id = ?
                """, code, name, registrationNo, status.wire(), id);
    }

    // ---------------------------------------------------------------- 部门

    public Optional<OrgUnit> findUnit(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, parent_id, legal_entity_id, code, name, unit_type,
                       status, sort_order, created_at
                  FROM organization_unit WHERE id = ?
                """, UNIT_MAPPER, id).stream().findFirst();
    }

    public List<OrgUnit> listUnits() {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, parent_id, legal_entity_id, code, name, unit_type,
                       status, sort_order, created_at
                  FROM organization_unit
                 ORDER BY sort_order, code
                """, UNIT_MAPPER);
    }

    public Optional<UUID> findParentId(UUID id) {
        // 根部门的 parent_id 为 NULL，不能直接 stream().findFirst()：元素为 null 会抛 NPE。
        List<UUID> rows = jdbcTemplate.query("SELECT parent_id FROM organization_unit WHERE id = ?",
                (rs, rowNum) -> rs.getObject("parent_id", UUID.class), id);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public void insertUnit(UUID id, UUID parentId, UUID legalEntityId, String code, String name,
                           OrgUnit.UnitType unitType, int sortOrder) {
        jdbcTemplate.update("""
                INSERT INTO organization_unit
                    (id, tenant_id, parent_id, legal_entity_id, code, name, unit_type, sort_order)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
                """, id, parentId, legalEntityId, code, name, unitType.wire(), sortOrder);
    }

    public int updateUnit(UUID id, String name, OrgUnit.UnitType unitType, int sortOrder) {
        return jdbcTemplate.update("""
                UPDATE organization_unit
                   SET name = ?, unit_type = ?, sort_order = ?, updated_at = now()
                 WHERE id = ?
                """, name, unitType.wire(), sortOrder, id);
    }

    public int moveUnit(UUID id, UUID parentId, int sortOrder) {
        return jdbcTemplate.update("""
                UPDATE organization_unit
                   SET parent_id = ?, sort_order = ?, updated_at = now()
                 WHERE id = ?
                """, parentId, sortOrder, id);
    }

    public int archiveUnit(UUID id) {
        return jdbcTemplate.update("""
                UPDATE organization_unit SET status = 'archived', updated_at = now()
                 WHERE id = ? AND status = 'active'
                """, id);
    }

    public boolean hasActiveChildren(UUID id) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM organization_unit WHERE parent_id = ? AND status = 'active'
                """, Long.class, id);
        return count != null && count > 0;
    }

    public boolean hasActivePositions(UUID id) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM organization_position
                 WHERE org_unit_id = ? AND status = 'active'
                """, Long.class, id);
        return count != null && count > 0;
    }

    public List<OrgUnit> subtree(UUID unitId) {
        return jdbcTemplate.query("""
                WITH RECURSIVE subtree AS (
                    SELECT id FROM organization_unit WHERE id = ?
                    UNION ALL
                    SELECT u.id FROM organization_unit u JOIN subtree s ON u.parent_id = s.id
                )
                SELECT u.id, u.tenant_id, u.parent_id, u.legal_entity_id, u.code, u.name,
                       u.unit_type, u.status, u.sort_order, u.created_at
                  FROM organization_unit u
                  JOIN subtree s ON s.id = u.id
                 ORDER BY u.sort_order, u.code
                """, UNIT_MAPPER, unitId);
    }

    // ---------------------------------------------------------------- 职级

    public Optional<JobLevel> findJobLevel(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, code, name, rank, status, created_at
                  FROM organization_job_level WHERE id = ?
                """, JOB_LEVEL_MAPPER, id).stream().findFirst();
    }

    public List<JobLevel> listJobLevels() {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, code, name, rank, status, created_at
                  FROM organization_job_level ORDER BY rank, code
                """, JOB_LEVEL_MAPPER);
    }

    public void insertJobLevel(UUID id, String code, String name, int rank) {
        jdbcTemplate.update("""
                INSERT INTO organization_job_level
                    (id, tenant_id, code, name, rank)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                """, id, code, name, rank);
    }

    public int updateJobLevel(UUID id, String code, String name, int rank, MasterDataStatus status) {
        return jdbcTemplate.update("""
                UPDATE organization_job_level
                   SET code = ?, name = ?, rank = ?, status = ?, updated_at = now()
                 WHERE id = ?
                """, code, name, rank, status.wire(), id);
    }

    // ---------------------------------------------------------------- 岗位

    public Optional<Job> findJob(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, code, name, job_family, default_level_id, status, created_at
                  FROM organization_job WHERE id = ?
                """, JOB_MAPPER, id).stream().findFirst();
    }

    public List<Job> listJobs() {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, code, name, job_family, default_level_id, status, created_at
                  FROM organization_job ORDER BY code
                """, JOB_MAPPER);
    }

    public void insertJob(UUID id, String code, String name, String jobFamily, UUID defaultLevelId) {
        jdbcTemplate.update("""
                INSERT INTO organization_job
                    (id, tenant_id, code, name, job_family, default_level_id)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
                """, id, code, name, jobFamily, defaultLevelId);
    }

    public int updateJob(UUID id, String code, String name, String jobFamily, UUID defaultLevelId,
                         MasterDataStatus status) {
        return jdbcTemplate.update("""
                UPDATE organization_job
                   SET code = ?, name = ?, job_family = ?, default_level_id = ?,
                       status = ?, updated_at = now()
                 WHERE id = ?
                """, code, name, jobFamily, defaultLevelId, status.wire(), id);
    }

    // ---------------------------------------------------------------- 职位

    public Optional<Position> findPosition(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, org_unit_id, job_id, code, name, headcount_limit,
                       status, created_at
                  FROM organization_position WHERE id = ?
                """, POSITION_MAPPER, id).stream().findFirst();
    }

    public List<Position> listPositions(UUID orgUnitId) {
        if (orgUnitId == null) {
            return jdbcTemplate.query("""
                    SELECT id, tenant_id, org_unit_id, job_id, code, name, headcount_limit,
                           status, created_at
                      FROM organization_position ORDER BY code
                    """, POSITION_MAPPER);
        }
        return jdbcTemplate.query("""
                SELECT id, tenant_id, org_unit_id, job_id, code, name, headcount_limit,
                       status, created_at
                  FROM organization_position WHERE org_unit_id = ? ORDER BY code
                """, POSITION_MAPPER, orgUnitId);
    }

    public void insertPosition(UUID id, UUID orgUnitId, UUID jobId, String code, String name,
                               Integer headcountLimit) {
        jdbcTemplate.update("""
                INSERT INTO organization_position
                    (id, tenant_id, org_unit_id, job_id, code, name, headcount_limit)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
                """, id, orgUnitId, jobId, code, name, headcountLimit);
    }

    public int updatePosition(UUID id, UUID orgUnitId, UUID jobId, String code, String name,
                              Integer headcountLimit, MasterDataStatus status) {
        return jdbcTemplate.update("""
                UPDATE organization_position
                   SET org_unit_id = ?, job_id = ?, code = ?, name = ?, headcount_limit = ?,
                       status = ?, updated_at = now()
                 WHERE id = ?
                """, orgUnitId, jobId, code, name, headcountLimit, status.wire(), id);
    }

    // ---------------------------------------------------------------- 成本中心

    public Optional<CostCenter> findCostCenter(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, code, name, legal_entity_id, status, created_at
                  FROM organization_cost_center WHERE id = ?
                """, COST_CENTER_MAPPER, id).stream().findFirst();
    }

    public List<CostCenter> listCostCenters() {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, code, name, legal_entity_id, status, created_at
                  FROM organization_cost_center ORDER BY code
                """, COST_CENTER_MAPPER);
    }

    public void insertCostCenter(UUID id, String code, String name, UUID legalEntityId) {
        jdbcTemplate.update("""
                INSERT INTO organization_cost_center
                    (id, tenant_id, code, name, legal_entity_id)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                """, id, code, name, legalEntityId);
    }

    public int updateCostCenter(UUID id, String code, String name, UUID legalEntityId,
                                MasterDataStatus status) {
        return jdbcTemplate.update("""
                UPDATE organization_cost_center
                   SET code = ?, name = ?, legal_entity_id = ?, status = ?, updated_at = now()
                 WHERE id = ?
                """, code, name, legalEntityId, status.wire(), id);
    }

    // ---------------------------------------------------------------- 地点

    public Optional<Location> findLocation(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, code, name, address, timezone, status, created_at
                  FROM organization_location WHERE id = ?
                """, LOCATION_MAPPER, id).stream().findFirst();
    }

    public List<Location> listLocations() {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, code, name, address, timezone, status, created_at
                  FROM organization_location ORDER BY code
                """, LOCATION_MAPPER);
    }

    public void insertLocation(UUID id, String code, String name, String address, String timezone) {
        jdbcTemplate.update("""
                INSERT INTO organization_location
                    (id, tenant_id, code, name, address, timezone)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
                """, id, code, name, address, timezone);
    }

    public int updateLocation(UUID id, String code, String name, String address, String timezone,
                              MasterDataStatus status) {
        return jdbcTemplate.update("""
                UPDATE organization_location
                   SET code = ?, name = ?, address = ?, timezone = ?, status = ?, updated_at = now()
                 WHERE id = ?
                """, code, name, address, timezone, status.wire(), id);
    }

    // ---------------------------------------------------------------- 影响计数

    public int countDescendants(UUID unitId) {
        Integer value = jdbcTemplate.queryForObject("""
                WITH RECURSIVE subtree AS (
                    SELECT id FROM organization_unit WHERE id = ?
                    UNION ALL
                    SELECT u.id FROM organization_unit u JOIN subtree s ON u.parent_id = s.id
                )
                SELECT count(*) - 1 FROM subtree
                """, Integer.class, unitId);
        return value == null ? 0 : value;
    }

    public int countEmploymentsInSubtree(UUID unitId, LocalDate today) {
        Integer value = jdbcTemplate.queryForObject("""
                WITH RECURSIVE subtree AS (
                    SELECT id FROM organization_unit WHERE id = ?
                    UNION ALL
                    SELECT u.id FROM organization_unit u JOIN subtree s ON u.parent_id = s.id
                )
                SELECT count(DISTINCT a.employment_id)
                  FROM organization_employment_assignment a
                  JOIN organization_employment e ON e.id = a.employment_id AND e.status = 'active'
                  JOIN organization_position p ON p.id = a.position_id
                 WHERE p.org_unit_id IN (SELECT id FROM subtree)
                   AND a.effective_from <= ?
                   AND (a.effective_to IS NULL OR a.effective_to > ?)
                """, Integer.class, unitId, today, today);
        return value == null ? 0 : value;
    }

    public int countActivePositionsInSubtree(UUID unitId) {
        Integer value = jdbcTemplate.queryForObject("""
                WITH RECURSIVE subtree AS (
                    SELECT id FROM organization_unit WHERE id = ?
                    UNION ALL
                    SELECT u.id FROM organization_unit u JOIN subtree s ON u.parent_id = s.id
                )
                SELECT count(*) FROM organization_position
                 WHERE org_unit_id IN (SELECT id FROM subtree) AND status = 'active'
                """, Integer.class, unitId);
        return value == null ? 0 : value;
    }

    public int countReportingLinesInSubtree(UUID unitId, LocalDate today) {
        Integer value = jdbcTemplate.queryForObject("""
                WITH RECURSIVE subtree AS (
                    SELECT id FROM organization_unit WHERE id = ?
                    UNION ALL
                    SELECT u.id FROM organization_unit u JOIN subtree s ON u.parent_id = s.id
                ), members AS (
                    SELECT DISTINCT a.employment_id
                      FROM organization_employment_assignment a
                      JOIN organization_position p ON p.id = a.position_id
                     WHERE p.org_unit_id IN (SELECT id FROM subtree)
                       AND a.effective_from <= ?
                       AND (a.effective_to IS NULL OR a.effective_to > ?)
                )
                SELECT count(*) FROM organization_reporting_line r
                 WHERE (r.subordinate_employment_id IN (SELECT employment_id FROM members)
                        OR r.manager_employment_id IN (SELECT employment_id FROM members))
                   AND r.effective_from <= ?
                   AND (r.effective_to IS NULL OR r.effective_to > ?)
                """, Integer.class, unitId, today, today, today, today);
        return value == null ? 0 : value;
    }

    public int countDelegationsInSubtree(UUID unitId, LocalDate today) {
        Integer value = jdbcTemplate.queryForObject("""
                WITH RECURSIVE subtree AS (
                    SELECT id FROM organization_unit WHERE id = ?
                    UNION ALL
                    SELECT u.id FROM organization_unit u JOIN subtree s ON u.parent_id = s.id
                )
                SELECT count(*) FROM organization_delegation
                 WHERE scope_org_unit_id IN (SELECT id FROM subtree)
                   AND effective_from <= ? AND effective_to > ?
                """, Integer.class, unitId, today, today);
        return value == null ? 0 : value;
    }

    public int countScheduledChangesInSubtree(UUID unitId) {
        Integer value = jdbcTemplate.queryForObject("""
                WITH RECURSIVE subtree AS (
                    SELECT id FROM organization_unit WHERE id = ?
                    UNION ALL
                    SELECT u.id FROM organization_unit u JOIN subtree s ON u.parent_id = s.id
                )
                SELECT count(*) FROM organization_change
                 WHERE status = 'scheduled' AND target_id IN (SELECT id FROM subtree)
                """, Integer.class, unitId);
        return value == null ? 0 : value;
    }
}
