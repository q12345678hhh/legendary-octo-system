package com.acme.platform.department.insight;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 管理洞察读模型。
 *
 * <p>只读取员工已提交/已通过的数据；草稿、返回中与未提交条目永不参与统计。
 * 所有查询都在 {@code TenantTransactions.inTenant(...)} 内执行。
 */
@Component
public class InsightStore {

    private final JdbcTemplate jdbcTemplate;

    public InsightStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Setting setting() {
        return jdbcTemplate.query("""
                        SELECT min_group_size, max_daily_minutes
                          FROM worklog_setting
                        """, (rs, rowNum) -> new Setting(
                        rs.getInt("min_group_size"), rs.getInt("max_daily_minutes")))
                .stream().findFirst()
                .orElse(new Setting(5, 1440));
    }

    public void updateMinGroupSize(int minGroupSize, String updatedBy) {
        jdbcTemplate.update("""
                INSERT INTO worklog_setting (tenant_id, min_group_size, updated_by)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE
                   SET min_group_size = EXCLUDED.min_group_size,
                       updated_by = EXCLUDED.updated_by,
                       updated_at = now()
                """, minGroupSize, updatedBy);
    }

    /** 部门整体（或指定人员集合）的已提交工时、任务与周报状态。 */
    public Aggregate aggregate(List<UUID> personIds, UUID orgUnitId, LocalDate from,
                               LocalDate to) {
        if (personIds.isEmpty()) {
            return Aggregate.empty();
        }
        String in = placeholders(personIds.size());
        List<Object> dayArgs = new ArrayList<>();
        dayArgs.add(from);
        dayArgs.add(to);
        dayArgs.addAll(personIds);

        DayTotals dayTotals = jdbcTemplate.queryForObject("""
                SELECT count(*) AS submitted_days,
                       count(DISTINCT person_id) AS contributors
                  FROM worklog_day
                 WHERE work_date BETWEEN ? AND ?
                   AND state IN ('submitted', 'approved')
                   AND person_id IN (%s)
                """.formatted(in), (rs, rowNum) -> new DayTotals(
                rs.getInt("submitted_days"), rs.getInt("contributors")), dayArgs.toArray());

        List<Object> minuteArgs = new ArrayList<>();
        minuteArgs.add(from);
        minuteArgs.add(to);
        minuteArgs.addAll(personIds);
        Long totalMinutes = jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(e.duration_minutes), 0)
                  FROM worklog_entry e
                  JOIN worklog_day d
                    ON d.person_id = e.person_id AND d.work_date = e.work_date
                 WHERE e.deleted_at IS NULL
                   AND d.state IN ('submitted', 'approved')
                   AND d.work_date BETWEEN ? AND ?
                   AND e.person_id IN (%s)
                """.formatted(in), Long.class, minuteArgs.toArray());

        Map<String, Integer> taskStatus = new LinkedHashMap<>();
        int overdueTasks = 0;
        List<Map<String, Object>> taskRows = orgUnitId == null
                ? jdbcTemplate.queryForList("""
                        SELECT t.status,
                               count(*) AS total,
                               count(*) FILTER (WHERE t.due_date < current_date
                                                   AND t.status <> 'done') AS overdue
                          FROM collab_task t
                         WHERE t.assignee_person_id IN (%s)
                         GROUP BY t.status
                        """.formatted(in), personIds.toArray())
                : jdbcTemplate.queryForList("""
                        SELECT t.status,
                               count(*) AS total,
                               count(*) FILTER (WHERE t.due_date < current_date
                                                   AND t.status <> 'done') AS overdue
                          FROM collab_task t
                          LEFT JOIN collab_project p ON p.id = t.project_id
                         WHERE t.org_unit_id = ? OR p.org_unit_id = ?
                         GROUP BY t.status
                        """, orgUnitId, orgUnitId);
        for (Map<String, Object> row : taskRows) {
            taskStatus.put((String) row.get("status"), ((Number) row.get("total")).intValue());
            overdueTasks += ((Number) row.get("overdue")).intValue();
        }

        List<Object> reportArgs = new ArrayList<>();
        reportArgs.add(from);
        reportArgs.add(to);
        reportArgs.addAll(personIds);
        Map<String, Integer> reportStatus = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                SELECT status, count(*) AS total FROM (
                    SELECT DISTINCT ON (person_id, week_start) status
                      FROM worklog_weekly_report
                     WHERE week_start BETWEEN ? AND ?
                       AND person_id IN (%s) AND version >= 1
                     ORDER BY person_id, week_start, version DESC
                ) latest
                GROUP BY status
                """.formatted(in), reportArgs.toArray())) {
            reportStatus.put((String) row.get("status"), ((Number) row.get("total")).intValue());
        }

        List<Object> weekArgs = new ArrayList<>();
        weekArgs.add(from);
        weekArgs.add(to);
        weekArgs.addAll(personIds);
        List<WeekAggregate> weeks = jdbcTemplate.query("""
                SELECT date_trunc('week', work_date::timestamp)::date AS week_start,
                       count(*) FILTER (WHERE state IN ('submitted', 'approved')) AS submitted_days,
                       count(DISTINCT person_id) FILTER (WHERE state IN ('submitted', 'approved'))
                           AS contributors
                  FROM worklog_day
                 WHERE work_date BETWEEN ? AND ? AND person_id IN (%s)
                 GROUP BY 1
                HAVING count(*) FILTER (WHERE state IN ('submitted', 'approved')) > 0
                 ORDER BY 1
                """.formatted(in), (rs, rowNum) -> new WeekAggregate(
                rs.getObject("week_start", LocalDate.class),
                rs.getInt("submitted_days"),
                rs.getInt("contributors")), weekArgs.toArray());

        return new Aggregate(dayTotals.contributors(), dayTotals.submittedDays(),
                totalMinutes == null ? 0L : totalMinutes, taskStatus, overdueTasks,
                reportStatus, weeks);
    }

    /** 下钻明细：只返回已提交口径，不含草稿正文。 */
    public List<PersonRow> people(List<UUID> personIds, LocalDate from, LocalDate to) {
        if (personIds.isEmpty()) {
            return List.of();
        }
        String in = placeholders(personIds.size());
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        args.addAll(personIds);
        args.add(from);
        args.add(to);
        args.addAll(personIds);
        args.addAll(personIds);
        return jdbcTemplate.query("""
                WITH days AS (
                    SELECT person_id, count(*) AS submitted_days
                      FROM worklog_day
                     WHERE work_date BETWEEN ? AND ?
                       AND state IN ('submitted', 'approved')
                       AND person_id IN (%1$s)
                     GROUP BY person_id
                ), minutes AS (
                    SELECT e.person_id, sum(e.duration_minutes) AS total_minutes
                      FROM worklog_entry e
                      JOIN worklog_day d
                        ON d.person_id = e.person_id AND d.work_date = e.work_date
                     WHERE e.deleted_at IS NULL
                       AND d.state IN ('submitted', 'approved')
                       AND d.work_date BETWEEN ? AND ?
                       AND e.person_id IN (%1$s)
                     GROUP BY e.person_id
                )
                SELECT p.id AS person_id,
                       p.display_name,
                       coalesce(days.submitted_days, 0) AS submitted_days,
                       coalesce(minutes.total_minutes, 0) AS total_minutes
                  FROM organization_person p
                  LEFT JOIN days ON days.person_id = p.id
                  LEFT JOIN minutes ON minutes.person_id = p.id
                 WHERE p.id IN (%1$s)
                 ORDER BY p.display_name
                """.formatted(in), (rs, rowNum) -> new PersonRow(
                rs.getObject("person_id", UUID.class),
                rs.getString("display_name"),
                rs.getInt("submitted_days"),
                rs.getLong("total_minutes")), args.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    public record Setting(int minGroupSize, int maxDailyMinutes) {
    }

    public record DayTotals(int submittedDays, int contributors) {
    }

    public record WeekAggregate(LocalDate weekStart, int submittedDays, int contributors) {
    }

    public record Aggregate(int contributors, int submittedDays, long totalMinutes,
                            Map<String, Integer> taskStatus, int overdueTasks,
                            Map<String, Integer> reportStatus, List<WeekAggregate> weeks) {

        static Aggregate empty() {
            return new Aggregate(0, 0, 0L, Map.of(), 0, Map.of(), List.of());
        }
    }

    public record PersonRow(UUID personId, String displayName, int submittedDays,
                            long totalMinutes) {
    }
}
