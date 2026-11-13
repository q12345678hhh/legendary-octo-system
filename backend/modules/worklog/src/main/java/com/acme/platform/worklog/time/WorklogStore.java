package com.acme.platform.worklog.time;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 工时、日快照、周报与设置的 JDBC 存储；调用方必须处于租户事务内。 */
@Component
public class WorklogStore {

    private final JdbcTemplate jdbcTemplate;

    public WorklogStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------ 草稿条目

    public List<WorklogEntry> entries(UUID personId, LocalDate date) {
        return jdbcTemplate.query("""
                SELECT id, person_id, employment_id, work_date, starts_at, ends_at,
                       duration_minutes, project_id, task_id, description, source_type,
                       client_entry_id, draft_version
                  FROM worklog_entry
                 WHERE person_id = ?
                   AND work_date = ?
                   AND deleted_at IS NULL
                 ORDER BY starts_at NULLS LAST, created_at
                """, WorklogStore::mapEntry, personId, date);
    }

    public Optional<WorklogEntry> entry(UUID id) {
        return jdbcTemplate.query("""
                        SELECT id, person_id, employment_id, work_date, starts_at, ends_at,
                               duration_minutes, project_id, task_id, description, source_type,
                               client_entry_id, draft_version
                          FROM worklog_entry
                         WHERE id = ? AND deleted_at IS NULL
                        """, WorklogStore::mapEntry, id)
                .stream().findFirst();
    }

    public boolean clientEntryExists(UUID personId, String clientEntryId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM worklog_entry
                 WHERE person_id = ? AND client_entry_id = ?
                """, Integer.class, personId, clientEntryId);
        return count != null && count > 0;
    }

    public UUID insertEntry(UUID personId, UUID employmentId, LocalDate workDate,
                            OffsetDateTime startsAt, OffsetDateTime endsAt, int minutes,
                            UUID projectId, UUID taskId, String description, String clientEntryId,
                            int draftVersion) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO worklog_entry
                    (tenant_id, person_id, employment_id, work_date, starts_at, ends_at,
                     duration_minutes, project_id, task_id, description, source_type,
                     client_entry_id, draft_version)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, 'manual', ?, ?)
                RETURNING id
                """, UUID.class, personId, employmentId, workDate, startsAt, endsAt, minutes,
                projectId, taskId, description, clientEntryId, draftVersion);
    }

    public void updateEntry(UUID id, OffsetDateTime startsAt, OffsetDateTime endsAt, int minutes,
                            UUID projectId, UUID taskId, String description) {
        jdbcTemplate.update("""
                UPDATE worklog_entry
                   SET starts_at = ?, ends_at = ?, duration_minutes = ?, project_id = ?,
                       task_id = ?, description = ?, updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                """, startsAt, endsAt, minutes, projectId, taskId, description, id);
    }

    public void softDelete(UUID id) {
        jdbcTemplate.update("""
                UPDATE worklog_entry SET deleted_at = now(), updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                """, id);
    }

    public int totalMinutes(UUID personId, LocalDate date, UUID excludingId) {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(duration_minutes), 0)
                  FROM worklog_entry
                 WHERE person_id = ? AND work_date = ? AND deleted_at IS NULL
                   AND (? IS NULL OR id <> ?)
                """, Integer.class, personId, date, excludingId, excludingId);
        return total == null ? 0 : total;
    }

    public List<WorklogEntry> overlapping(UUID personId, LocalDate date, OffsetDateTime start,
                                          OffsetDateTime end, UUID excludingId) {
        return jdbcTemplate.query("""
                SELECT id, person_id, employment_id, work_date, starts_at, ends_at,
                       duration_minutes, project_id, task_id, description, source_type,
                       client_entry_id, draft_version
                  FROM worklog_entry
                 WHERE person_id = ? AND work_date = ? AND deleted_at IS NULL
                   AND (? IS NULL OR id <> ?)
                   AND starts_at IS NOT NULL AND ends_at IS NOT NULL
                   AND starts_at < ? AND ends_at > ?
                """, WorklogStore::mapEntry, personId, date, excludingId, excludingId, end, start);
    }

    // ------------------------------------------------------------------ 日状态与快照

    public Optional<DayState> day(UUID personId, LocalDate date) {
        return jdbcTemplate.query("""
                        SELECT person_id, work_date, state, draft_version, current_submission_id
                          FROM worklog_day
                         WHERE person_id = ? AND work_date = ?
                        """, (rs, rowNum) -> new DayState(
                        rs.getObject("person_id", UUID.class),
                        rs.getObject("work_date", LocalDate.class),
                        rs.getString("state"),
                        rs.getInt("draft_version"),
                        rs.getObject("current_submission_id", UUID.class)),
                personId, date).stream().findFirst();
    }

    public DayState lockDay(UUID personId, LocalDate date) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO worklog_day (tenant_id, person_id, work_date)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
                ON CONFLICT (tenant_id, person_id, work_date) DO UPDATE
                   SET updated_at = now()
                RETURNING person_id, work_date, state, draft_version, current_submission_id
                """, (rs, rowNum) -> new DayState(
                        rs.getObject("person_id", UUID.class),
                        rs.getObject("work_date", LocalDate.class),
                        rs.getString("state"),
                        rs.getInt("draft_version"),
                        rs.getObject("current_submission_id", UUID.class)),
                personId, date);
    }

    public UUID insertSubmission(UUID personId, LocalDate workDate, int version, String snapshot,
                                 String hash, int totalMinutes, UUID submittedBy) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO worklog_submission
                    (tenant_id, person_id, work_date, version, snapshot, snapshot_hash,
                     total_minutes, submitted_by_person_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                RETURNING id
                """, UUID.class, personId, workDate, version, snapshot, hash, totalMinutes,
                submittedBy);
    }

    public void markDaySubmitted(UUID personId, LocalDate date, int version, UUID submissionId) {
        jdbcTemplate.update("""
                UPDATE worklog_day
                   SET state = 'submitted', draft_version = ?, current_submission_id = ?,
                       updated_at = now()
                 WHERE person_id = ? AND work_date = ?
                """, version, submissionId, personId, date);
    }

    public int nextSubmissionVersion(UUID personId, LocalDate date) {
        Integer next = jdbcTemplate.queryForObject("""
                SELECT coalesce(max(version), 0) + 1
                  FROM worklog_submission
                 WHERE person_id = ? AND work_date = ?
                """, Integer.class, personId, date);
        return next == null ? 1 : next;
    }

    public Optional<Submission> submission(UUID id) {
        return jdbcTemplate.query("""
                        SELECT id, person_id, work_date, version, status, snapshot::text AS snapshot,
                               total_minutes, submitted_at
                          FROM worklog_submission
                         WHERE id = ?
                        """, (rs, rowNum) -> new Submission(
                        rs.getObject("id", UUID.class),
                        rs.getObject("person_id", UUID.class),
                        rs.getObject("work_date", LocalDate.class),
                        rs.getInt("version"),
                        rs.getString("status"),
                        rs.getString("snapshot"),
                        rs.getInt("total_minutes"),
                        rs.getObject("submitted_at", OffsetDateTime.class)),
                id).stream().findFirst();
    }

    public Optional<Submission> currentSubmission(UUID personId, LocalDate date) {
        return jdbcTemplate.query("""
                        SELECT s.id, s.person_id, s.work_date, s.version, s.status,
                               s.snapshot::text AS snapshot, s.total_minutes, s.submitted_at
                          FROM worklog_day d
                          JOIN worklog_submission s ON s.id = d.current_submission_id
                         WHERE d.person_id = ? AND d.work_date = ?
                        """, (rs, rowNum) -> new Submission(
                        rs.getObject("id", UUID.class),
                        rs.getObject("person_id", UUID.class),
                        rs.getObject("work_date", LocalDate.class),
                        rs.getInt("version"),
                        rs.getString("status"),
                        rs.getString("snapshot"),
                        rs.getInt("total_minutes"),
                        rs.getObject("submitted_at", OffsetDateTime.class)),
                personId, date).stream().findFirst();
    }

    public List<Submission> submissionsInWeek(UUID personId, LocalDate weekStart,
                                              LocalDate weekEnd) {
        return jdbcTemplate.query("""
                SELECT DISTINCT ON (work_date)
                       id, person_id, work_date, version, status, snapshot::text AS snapshot,
                       total_minutes, submitted_at
                  FROM worklog_submission
                 WHERE person_id = ?
                   AND work_date BETWEEN ? AND ?
                   AND status IN ('submitted', 'approved')
                 ORDER BY work_date, version DESC
                """, (rs, rowNum) -> new Submission(
                        rs.getObject("id", UUID.class),
                        rs.getObject("person_id", UUID.class),
                        rs.getObject("work_date", LocalDate.class),
                        rs.getInt("version"),
                        rs.getString("status"),
                        rs.getString("snapshot"),
                        rs.getInt("total_minutes"),
                        rs.getObject("submitted_at", OffsetDateTime.class)),
                personId, weekStart, weekEnd);
    }

    public void reviewSubmission(UUID id, String status, UUID reviewer, String comment) {
        jdbcTemplate.update("""
                UPDATE worklog_submission
                   SET status = ?, reviewed_at = now(), reviewed_by_person_id = ?, review_comment = ?
                 WHERE id = ? AND status = 'submitted'
                """, status, reviewer, comment, id);
    }

    public void updateDayState(UUID personId, LocalDate date, String state) {
        jdbcTemplate.update("""
                UPDATE worklog_day SET state = ?, updated_at = now()
                 WHERE person_id = ? AND work_date = ?
                """, state, personId, date);
    }

    public List<Submission> pendingSubmissions(Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(personIds.size(), "?"));
        return jdbcTemplate.query("""
                SELECT id, person_id, work_date, version, status, snapshot::text AS snapshot,
                       total_minutes, submitted_at
                  FROM worklog_submission
                 WHERE status = 'submitted' AND person_id IN (%s)
                 ORDER BY submitted_at
                """.formatted(placeholders), (rs, rowNum) -> new Submission(
                rs.getObject("id", UUID.class),
                rs.getObject("person_id", UUID.class),
                rs.getObject("work_date", LocalDate.class),
                rs.getInt("version"),
                rs.getString("status"),
                rs.getString("snapshot"),
                rs.getInt("total_minutes"),
                rs.getObject("submitted_at", OffsetDateTime.class)),
                personIds.toArray());
    }

    // ------------------------------------------------------------------ 周报

    public Optional<Report> report(UUID id) {
        return jdbcTemplate.query("""
                        SELECT id, person_id, week_start, week_end, version, status,
                               content::text AS content, submitted_at
                          FROM worklog_weekly_report
                         WHERE id = ?
                        """, WorklogStore::mapReport, id).stream().findFirst();
    }

    public Optional<Report> latestReport(UUID personId, LocalDate weekStart) {
        return jdbcTemplate.query("""
                        SELECT id, person_id, week_start, week_end, version, status,
                               content::text AS content, submitted_at
                          FROM worklog_weekly_report
                         WHERE person_id = ? AND week_start = ?
                         ORDER BY version DESC
                         LIMIT 1
                        """, WorklogStore::mapReport, personId, weekStart).stream().findFirst();
    }

    public Report insertReport(UUID personId, LocalDate weekStart, LocalDate weekEnd, int version,
                               String content) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO worklog_weekly_report
                    (tenant_id, person_id, week_start, week_end, version, content)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, CAST(? AS jsonb))
                RETURNING id, person_id, week_start, week_end, version, status,
                          content::text AS content, submitted_at
                """, WorklogStore::mapReport, personId, weekStart, weekEnd, version, content);
    }

    public void updateReportContent(UUID id, String content) {
        jdbcTemplate.update("""
                UPDATE worklog_weekly_report SET content = CAST(? AS jsonb)
                 WHERE id = ? AND status = 'draft'
                """, content, id);
    }

    public void submitReport(UUID id, String hash) {
        jdbcTemplate.update("""
                UPDATE worklog_weekly_report
                   SET status = 'submitted', snapshot_hash = ?, submitted_at = now()
                 WHERE id = ? AND status = 'draft'
                """, hash, id);
    }

    public void reviewReport(UUID id, String status, UUID reviewer, String comment) {
        jdbcTemplate.update("""
                UPDATE worklog_weekly_report
                   SET status = ?, reviewed_at = now(), reviewed_by_person_id = ?, review_comment = ?
                 WHERE id = ? AND status = 'submitted'
                """, status, reviewer, comment, id);
    }

    public void replaceReportSources(UUID reportId, Map<UUID, Integer> minutesBySubmission) {
        jdbcTemplate.update("DELETE FROM worklog_report_source WHERE report_id = ?", reportId);
        for (Map.Entry<UUID, Integer> entry : minutesBySubmission.entrySet()) {
            jdbcTemplate.update("""
                    INSERT INTO worklog_report_source
                        (tenant_id, report_id, submission_id, item_key, minutes)
                    VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                            ?, ?, 'total', ?)
                    """, reportId, entry.getKey(), entry.getValue());
        }
    }

    public List<Report> pendingReports(Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(personIds.size(), "?"));
        return jdbcTemplate.query("""
                SELECT id, person_id, week_start, week_end, version, status,
                       content::text AS content, submitted_at
                  FROM worklog_weekly_report
                 WHERE status = 'submitted' AND person_id IN (%s)
                 ORDER BY submitted_at
                """.formatted(placeholders), WorklogStore::mapReport, personIds.toArray());
    }

    public Setting setting() {
        return jdbcTemplate.query("""
                        SELECT min_group_size, max_daily_minutes
                          FROM worklog_setting
                         WHERE tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
                        """, (rs, rowNum) -> new Setting(
                        rs.getInt("min_group_size"), rs.getInt("max_daily_minutes")))
                .stream().findFirst().orElse(new Setting(5, 1440));
    }

    // ------------------------------------------------------------------ 批量状态

    public Map<UUID, DayState> dayStates(Collection<UUID> personIds, LocalDate date) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(personIds.size(), "?"));
        Map<UUID, DayState> result = new HashMap<>();
        Object[] args = new Object[personIds.size() + 1];
        args[0] = date;
        int index = 1;
        for (UUID personId : personIds) {
            args[index++] = personId;
        }
        jdbcTemplate.query("""
                SELECT person_id, work_date, state, draft_version, current_submission_id
                  FROM worklog_day
                 WHERE work_date = ? AND person_id IN (%s)
                """.formatted(placeholders), (rs) -> {
            DayState state = new DayState(
                    rs.getObject("person_id", UUID.class),
                    rs.getObject("work_date", LocalDate.class),
                    rs.getString("state"),
                    rs.getInt("draft_version"),
                    rs.getObject("current_submission_id", UUID.class));
            result.put(state.personId(), state);
        }, args);
        return Map.copyOf(result);
    }

    public Map<UUID, Integer> submittedTotals(Collection<UUID> submissionIds) {
        if (submissionIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(submissionIds.size(), "?"));
        Map<UUID, Integer> result = new HashMap<>();
        jdbcTemplate.query("""
                SELECT id, total_minutes FROM worklog_submission WHERE id IN (%s)
                """.formatted(placeholders), (rs) -> {
            result.put(rs.getObject("id", UUID.class), rs.getInt("total_minutes"));
        }, submissionIds.toArray());
        return Map.copyOf(result);
    }

    public Map<UUID, String> reportStates(Collection<UUID> personIds, LocalDate weekStart) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(personIds.size(), "?"));
        Map<UUID, String> result = new HashMap<>();
        Object[] args = new Object[personIds.size() + 1];
        args[0] = weekStart;
        int index = 1;
        for (UUID personId : personIds) {
            args[index++] = personId;
        }
        jdbcTemplate.query("""
                SELECT DISTINCT ON (person_id) person_id, status
                  FROM worklog_weekly_report
                 WHERE week_start = ? AND person_id IN (%s)
                 ORDER BY person_id, version DESC
                """.formatted(placeholders), (rs) -> {
            result.put(rs.getObject("person_id", UUID.class), rs.getString("status"));
        }, args);
        return Map.copyOf(result);
    }

    // ------------------------------------------------------------------ 映射

    private static WorklogEntry mapEntry(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new WorklogEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("person_id", UUID.class),
                rs.getObject("employment_id", UUID.class),
                rs.getObject("work_date", LocalDate.class),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class),
                rs.getInt("duration_minutes"),
                rs.getObject("project_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getString("description"),
                rs.getString("source_type"),
                rs.getString("client_entry_id"),
                rs.getInt("draft_version"));
    }

    private static Report mapReport(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new Report(
                rs.getObject("id", UUID.class),
                rs.getObject("person_id", UUID.class),
                rs.getObject("week_start", LocalDate.class),
                rs.getObject("week_end", LocalDate.class),
                rs.getInt("version"),
                rs.getString("status"),
                rs.getString("content"),
                rs.getObject("submitted_at", OffsetDateTime.class));
    }

    public record DayState(UUID personId, LocalDate workDate, String state, int draftVersion,
                           UUID currentSubmissionId) {
    }

    public record Submission(UUID id, UUID personId, LocalDate workDate, int version,
                             String status, String snapshot, int totalMinutes,
                             OffsetDateTime submittedAt) {
    }

    public record Report(UUID id, UUID personId, LocalDate weekStart, LocalDate weekEnd,
                         int version, String status, String content,
                         OffsetDateTime submittedAt) {
    }

    public record Setting(int minGroupSize, int maxDailyMinutes) {
    }
}
