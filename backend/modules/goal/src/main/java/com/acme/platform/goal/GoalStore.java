package com.acme.platform.goal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 目标与关键结果的 JDBC 读写。
 *
 * <p>所有方法都假设调用方已处于 {@code TenantTransactions.inTenant(...)} 事务中，
 * tenant_id 由 RLS 上下文提供。
 */
@Component
public class GoalStore {

    private final JdbcTemplate jdbcTemplate;

    public GoalStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record GoalRow(UUID id, UUID ownerPersonId, String title, String description,
                          String period, LocalDate dueDate, int progress, String status,
                          String scopeType, UUID orgUnitId, long version,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record KeyResultRow(UUID id, UUID goalId, String title, BigDecimal targetValue,
                               BigDecimal currentValue, String unit, int sortOrder,
                               long version) {
    }

    private static final String COLUMNS = """
            id, owner_person_id, title, description, period, due_date, progress, status,
            scope_type, org_unit_id, version, created_at, updated_at
            """;

    private static GoalRow goal(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GoalRow(rs.getObject("id", UUID.class),
                rs.getObject("owner_person_id", UUID.class), rs.getString("title"),
                rs.getString("description"), rs.getString("period"),
                rs.getObject("due_date", LocalDate.class), rs.getInt("progress"),
                rs.getString("status"), rs.getString("scope_type"),
                rs.getObject("org_unit_id", UUID.class), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    public List<GoalRow> listByOwner(UUID ownerPersonId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + """
                  FROM work_goal
                 WHERE owner_person_id = ?
                   AND scope_type = 'person'
                 ORDER BY status = 'active' DESC, due_date NULLS LAST, updated_at DESC
                """, (rs, rowNum) -> goal(rs), ownerPersonId);
    }

    public List<GoalRow> listByUnit(UUID orgUnitId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + """
                  FROM work_goal
                 WHERE scope_type = 'team'
                   AND org_unit_id = ?
                 ORDER BY status = 'active' DESC, due_date NULLS LAST, updated_at DESC
                """, (rs, rowNum) -> goal(rs), orgUnitId);
    }

    public Optional<GoalRow> find(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM work_goal WHERE id = ?",
                (rs, rowNum) -> goal(rs), id).stream().findFirst();
    }

    public UUID insert(UUID ownerPersonId, String title, String description, String period,
                       LocalDate dueDate, int progress, String actor) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO work_goal
                    (tenant_id, owner_person_id, title, description, period, due_date,
                     progress, created_by)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, ownerPersonId, title, description, period, dueDate, progress,
                actor == null ? "" : actor);
    }

    public UUID insertTeam(UUID ownerPersonId, UUID orgUnitId, String title, String description,
                           String period, LocalDate dueDate, String actor) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO work_goal
                    (tenant_id, owner_person_id, title, description, period, due_date,
                     progress, status, scope_type, org_unit_id, created_by)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, 0, 'active', 'team', ?, ?)
                RETURNING id
                """, UUID.class, ownerPersonId, title, description, period, dueDate, orgUnitId,
                actor == null ? "" : actor);
    }

    /** 乐观锁更新；返回 false 表示版本已被并发修改。 */
    public boolean update(UUID id, String title, String description, String period,
                          LocalDate dueDate, int progress, String status, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE work_goal
                   SET title = ?, description = ?, period = ?, due_date = ?, progress = ?,
                       status = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND version = ?
                """, title, description, period, dueDate, progress, status, id,
                expectedVersion) == 1;
    }

    // ------------------------------------------------------------ 关键结果

    private static final String KEY_RESULT_COLUMNS = """
            id, goal_id, title, target_value, current_value, unit, sort_order, version
            """;

    private static KeyResultRow keyResult(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new KeyResultRow(rs.getObject("id", UUID.class),
                rs.getObject("goal_id", UUID.class), rs.getString("title"),
                rs.getBigDecimal("target_value"), rs.getBigDecimal("current_value"),
                rs.getString("unit"), rs.getInt("sort_order"), rs.getLong("version"));
    }

    public List<KeyResultRow> listKeyResults(UUID goalId) {
        return jdbcTemplate.query("SELECT " + KEY_RESULT_COLUMNS + """
                  FROM work_goal_key_result
                 WHERE goal_id = ?
                 ORDER BY sort_order, created_at
                """, (rs, rowNum) -> keyResult(rs), goalId);
    }

    public int countKeyResults(UUID goalId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM work_goal_key_result WHERE goal_id = ?",
                Integer.class, goalId);
        return count == null ? 0 : count;
    }

    public Optional<KeyResultRow> findKeyResult(UUID id) {
        return jdbcTemplate.query("SELECT " + KEY_RESULT_COLUMNS
                        + " FROM work_goal_key_result WHERE id = ?",
                (rs, rowNum) -> keyResult(rs), id).stream().findFirst();
    }

    public UUID insertKeyResult(UUID goalId, String title, BigDecimal targetValue,
                                BigDecimal currentValue, String unit, int sortOrder) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO work_goal_key_result
                    (tenant_id, goal_id, title, target_value, current_value, unit, sort_order)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, goalId, title, targetValue, currentValue, unit, sortOrder);
    }

    /** 乐观锁更新当前值；返回 false 表示版本已被并发修改。 */
    public boolean updateKeyResultCurrent(UUID id, BigDecimal currentValue, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE work_goal_key_result
                   SET current_value = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND version = ?
                """, currentValue, id, expectedVersion) == 1;
    }
}
