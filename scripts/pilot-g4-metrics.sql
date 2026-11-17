-- WP04-06 试点指标口径（工时 / 语音 / 周报 / 单据预审）。
--
-- 用途：对真实试点数据按固定窗口聚合，作为 G4 评审的过程证据；
--       合成数据可以跑通口径，但不能替代「连续四周真实使用」。
--
-- 口径说明：
--   1. 语音确认率 = 窗口内 confirmed 录音数 / 窗口内全部录音数；
--   2. 周报提交率 = 有 submitted/approved 周报的「人×周」/ 应提交「人×周」；
--      应提交人周 = 在职且非老板的任职 × 窗口内每个周一；
--   3. 周报修改率 = 产生过 version > 1 的已提交周报 / 已提交周报；
--   4. 预审自动通过率 = post_actor_kind='ai' 的已入账单据 / 全部已入账单据；
--   5. 误拦截代理指标 = 人工复核通过数 / 人工复核总数（需结合每周抽样复核确认，
--      不能单独作为误拦截率结论）；
--   6. 人工复核时效 = 复核记录时间 - 单据创建时间，取平均小时数；
--   7. 工时录入耗时与员工不适感来自现场计时和问卷，不在本脚本内计算。
--
-- 使用方式：
--   psql -d platform -f scripts/pilot-g4-metrics.sql
--   默认窗口为「最近四个完整自然周」，即本周一往前 28 天到上周日；
--   试点期间每周固定跑一次，保证口径一致。

SELECT set_config('app.tenant_id', 'aaaaaaaa-0000-0000-0000-000000000001', true);

WITH params AS (
    SELECT (date_trunc('week', current_date) - interval '28 days')::date AS start_date,
           (date_trunc('week', current_date) - interval '1 day')::date AS end_date,
           'aaaaaaaa-0000-0000-0000-000000000001'::uuid AS tenant_id
),
voice AS (
    SELECT count(*) AS total,
           count(*) FILTER (WHERE status = 'confirmed') AS confirmed
      FROM worklog_voice_capture
     WHERE tenant_id = (SELECT tenant_id FROM params)
       AND recycled_at IS NULL
       AND created_at::date BETWEEN (SELECT start_date FROM params)
                                AND (SELECT end_date FROM params)
),
weeks AS (
    SELECT generate_series(
               date_trunc('week', (SELECT start_date FROM params))::date,
               (SELECT end_date FROM params),
               interval '7 days')::date AS week_start
),
cohort AS (
    SELECT e.person_id
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
expected_weeks AS (
    SELECT c.person_id, w.week_start
      FROM cohort c CROSS JOIN weeks w
),
report_flags AS (
    SELECT x.person_id, x.week_start,
           bool_or(r.status IN ('submitted', 'approved')) AS submitted,
           bool_or(r.version > 1) AS modified
      FROM expected_weeks x
      LEFT JOIN worklog_weekly_report r
             ON r.tenant_id = (SELECT tenant_id FROM params)
            AND r.person_id = x.person_id
            AND r.week_start = x.week_start
     GROUP BY x.person_id, x.week_start
),
reports AS (
    SELECT count(*) AS expected_weeks,
           count(*) FILTER (WHERE submitted) AS submitted_weeks,
           count(*) FILTER (WHERE submitted AND modified) AS modified_weeks
      FROM report_flags
),
docs AS (
    SELECT *
      FROM expense_document
     WHERE tenant_id = (SELECT tenant_id FROM params)
       AND recycled_at IS NULL
       AND created_at::date BETWEEN (SELECT start_date FROM params)
                                AND (SELECT end_date FROM params)
),
posted AS (
    SELECT count(*) AS total,
           count(*) FILTER (WHERE post_actor_kind = 'ai') AS by_ai
      FROM docs
     WHERE status = 'posted'
),
reviews AS (
    SELECT count(*) AS total,
           count(*) FILTER (WHERE rv.decision = 'approve') AS approved,
           avg(extract(epoch FROM rv.created_at - d.created_at) / 3600.0) AS avg_hours
      FROM expense_document_review rv
      JOIN docs d ON d.id = rv.document_id
     WHERE rv.tenant_id = (SELECT tenant_id FROM params)
)
SELECT '语音确认率' AS metric,
       CASE WHEN total = 0 THEN '无样本'
            ELSE round(confirmed * 100.0 / total, 1) || '% (' || confirmed || '/' || total || ')'
       END AS value,
       'confirmed / 窗口内全部录音' AS definition
  FROM voice
UNION ALL
SELECT '周报提交率',
       CASE WHEN expected_weeks = 0 THEN '无样本'
            ELSE round(submitted_weeks * 100.0 / expected_weeks, 1) || '% ('
                 || submitted_weeks || '/' || expected_weeks || ')'
       END,
       '已提交人周 / 应提交人周'
  FROM reports
UNION ALL
SELECT '周报修改率',
       CASE WHEN submitted_weeks = 0 THEN '无样本'
            ELSE round(modified_weeks * 100.0 / submitted_weeks, 1) || '% ('
                 || modified_weeks || '/' || submitted_weeks || ')'
       END,
       '产生 version>1 的已提交周报 / 已提交周报'
  FROM reports
UNION ALL
SELECT '预审自动通过率',
       CASE WHEN total = 0 THEN '无样本'
            ELSE round(by_ai * 100.0 / total, 1) || '% (' || by_ai || '/' || total || ')'
       END,
       'ai 入账 / 全部入账'
  FROM posted
UNION ALL
SELECT '误拦截代理指标',
       CASE WHEN total = 0 THEN '无样本'
            ELSE round(approved * 100.0 / total, 1) || '% (' || approved || '/' || total || ')'
       END,
       '人工复核通过 / 人工复核总数（需周会抽样复核）'
  FROM reviews
UNION ALL
SELECT '人工复核平均时效',
       CASE WHEN avg_hours IS NULL THEN '无样本'
            ELSE round(avg_hours::numeric, 1) || ' 小时'
       END,
       '复核时间 - 单据创建时间'
  FROM reviews
UNION ALL
SELECT '工时录入耗时', '需人工采集', '试点现场计时（秒/人日）'
UNION ALL
SELECT '员工不适感反馈', '需人工采集', '每周匿名问卷（1-5 分，含隐私担忧）';
