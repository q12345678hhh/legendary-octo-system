-- WP03-08 指标口径：两周工时与周报的提交率、退回率、主管处理时效。
--
-- 说明：
--   1. 该脚本对「真实数据」和「合成数据」使用同一口径，只按时间窗口聚合；
--   2. 应提交人日 = 窗口内每个工作日 × 在职且非老板的任职；
--      合成部门里老板（owner）不纳入工时考核，因此不进入分母；
--   3. 退回率按「发生过退回的人日」计算，同一天退回后重新提交只计一次；
--   4. 处理时效统计所有已审核快照（含退回后重新提交的第二次审核），
--      因此口径是「审核动作」而不是「人日」。
--
-- 使用方式：
--   psql -v start_date=2026-10-26 -v end_date=2026-11-06 -f scripts/worklog-two-week-metrics.sql
--   或直接修改下方 params 中的默认窗口。

SELECT set_config('app.tenant_id', 'aaaaaaaa-0000-0000-0000-000000000001', true);

WITH params AS (
    SELECT DATE '2026-10-26' AS start_date,
           DATE '2026-11-06' AS end_date,
           'aaaaaaaa-0000-0000-0000-000000000001'::uuid AS tenant_id
),
cohort AS (
    SELECT e.person_id, e.id AS employment_id
      FROM organization_employment e
     WHERE e.tenant_id = (SELECT tenant_id FROM params)
       AND e.status = 'active'
       AND e.effective_from <= (SELECT end_date FROM params)
       AND (e.effective_to IS NULL OR e.effective_to > (SELECT start_date FROM params))
       AND NOT EXISTS (
           SELECT 1 FROM organization_owner_role o
            WHERE o.tenant_id = e.tenant_id
              AND o.employment_id = e.id
              AND o.role_type = 'owner')
),
expected AS (
    SELECT c.person_id, d::date AS work_date
      FROM cohort c
      CROSS JOIN LATERAL generate_series(
          (SELECT start_date FROM params), (SELECT end_date FROM params),
          interval '1 day') d
     WHERE extract(isodow FROM d) <= 5
),
subs AS (
    SELECT person_id, work_date, status, submitted_at, reviewed_at
      FROM worklog_submission
     WHERE tenant_id = (SELECT tenant_id FROM params)
       AND work_date BETWEEN (SELECT start_date FROM params)
                         AND (SELECT end_date FROM params)
),
day_flags AS (
    SELECT person_id, work_date,
           bool_or(status IN ('submitted', 'approved', 'returned')) AS submitted,
           bool_or(status = 'returned') AS returned
      FROM subs
     GROUP BY person_id, work_date
),
review_durations AS (
    SELECT extract(epoch FROM reviewed_at - submitted_at) / 3600.0 AS hours
      FROM subs
     WHERE reviewed_at IS NOT NULL
),
weeks AS (
    SELECT c.person_id, w::date AS week_start
      FROM cohort c
      CROSS JOIN LATERAL (VALUES (DATE '2026-10-26'), (DATE '2026-11-02')) AS t(w)
),
report_flags AS (
    SELECT person_id, week_start,
           bool_or(status IN ('submitted', 'approved')) AS submitted,
           bool_or(status = 'returned') AS returned,
           bool_or(status = 'approved') AS approved
      FROM worklog_weekly_report
     WHERE tenant_id = (SELECT tenant_id FROM params)
       AND week_start IN (SELECT week_start FROM weeks)
     GROUP BY person_id, week_start
)
SELECT metric, value
  FROM (
    SELECT 1 AS ord, '统计窗口' AS metric,
           (SELECT start_date || ' ~ ' || end_date FROM params) AS value
    UNION ALL
    SELECT 2, '应提交人日（工作日 × 非老板在职）',
           (SELECT count(*)::text FROM expected)
    UNION ALL
    SELECT 3, '实际提交人日',
           (SELECT count(*)::text FROM day_flags WHERE submitted)
    UNION ALL
    SELECT 4, '提交率',
           (SELECT round(100.0
                         * (SELECT count(*) FROM day_flags WHERE submitted)
                         / nullif((SELECT count(*) FROM expected), 0), 1)::text || '%')
    UNION ALL
    SELECT 5, '发生退回的人日',
           (SELECT count(*)::text FROM day_flags WHERE returned)
    UNION ALL
    SELECT 6, '退回率',
           (SELECT round(100.0 * count(*) FILTER (WHERE returned)
                         / nullif(count(*) FILTER (WHERE submitted), 0), 1)::text || '%'
              FROM day_flags)
    UNION ALL
    SELECT 7, '审核动作数',
           (SELECT count(*)::text FROM review_durations)
    UNION ALL
    SELECT 8, '平均处理时效（小时）',
           (SELECT round(avg(hours)::numeric, 1)::text FROM review_durations)
    UNION ALL
    SELECT 9, '处理时效中位数（小时）',
           (SELECT round(percentile_cont(0.5) WITHIN GROUP (ORDER BY hours)::numeric, 1)::text
              FROM review_durations)
    UNION ALL
    SELECT 10, '处理时效 P90（小时）',
           (SELECT round(percentile_cont(0.9) WITHIN GROUP (ORDER BY hours)::numeric, 1)::text
              FROM review_durations)
    UNION ALL
    SELECT 11, '应提交周报（人 × 周）',
           (SELECT count(*)::text FROM weeks)
    UNION ALL
    SELECT 12, '已提交周报',
           (SELECT count(*)::text FROM report_flags WHERE submitted)
    UNION ALL
    SELECT 13, '周报退回率',
           (SELECT round(100.0 * count(*) FILTER (WHERE returned)
                         / nullif(count(*) FILTER (WHERE submitted), 0), 1)::text || '%'
              FROM report_flags)
    UNION ALL
    SELECT 14, '周报通过率',
           (SELECT round(100.0 * count(*) FILTER (WHERE approved)
                         / nullif(count(*) FILTER (WHERE submitted), 0), 1)::text || '%'
              FROM report_flags)
  ) AS metrics
 ORDER BY ord;

-- 按人拆分，便于发现个别异常
WITH params AS (
    SELECT DATE '2026-10-26' AS start_date,
           DATE '2026-11-06' AS end_date,
           'aaaaaaaa-0000-0000-0000-000000000001'::uuid AS tenant_id
),
bounds AS (
    SELECT start_date, end_date FROM params
),
per_person AS (
    SELECT p.display_name,
           count(DISTINCT s.work_date) AS submitted_days,
           count(DISTINCT s.work_date) FILTER (WHERE s.status = 'returned') AS returned_days,
           coalesce(round(avg(extract(epoch FROM s.reviewed_at - s.submitted_at) / 3600.0)
                          FILTER (WHERE s.reviewed_at IS NOT NULL)::numeric, 1), 0) AS avg_hours
      FROM organization_person p
      LEFT JOIN worklog_submission s
        ON s.person_id = p.id
       AND s.tenant_id = (SELECT tenant_id FROM params)
       AND s.work_date BETWEEN (SELECT start_date FROM bounds)
                           AND (SELECT end_date FROM bounds)
     WHERE p.tenant_id = (SELECT tenant_id FROM params)
     GROUP BY p.display_name
)
SELECT * FROM per_person ORDER BY display_name;
