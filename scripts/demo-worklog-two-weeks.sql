-- WP03-08 两周工时与周报数据集（**合成演示数据，不是真实员工数据**）。
--
-- 目的：在接入真实试点用户之前，用可重复、可回溯的合成数据跑通
--   工时草稿 → 日快照 → 主管审核（通过/退回/重新提交）→ 周报 → 周报审核
-- 全链路，并据此产出提交率、退回率、处理时效等指标的计算口径。
--
-- 重要声明：
--   1. 本脚本写入的是**模拟数据**，不得作为「连续两周真实运行」的证据；
--      Phase 3 的 G3 门禁仍需要真实用户连续使用两周后补录。
--   2. 数据窗口固定为 2026-10-26（周一）至 2026-11-06（周五）两个完整工作周，
--      另加 2026-11-09 至 2026-11-12 当前周的部分数据，便于页面演示。
--   3. 全部主键由 md5(固定文本) 生成，配合 ON CONFLICT DO NOTHING 可重复执行。
--
-- 使用方式（宿主 Windows，WSL pglab 中的 platform-pg-dev）：
--   Get-Content scripts/demo-worklog-two-weeks.sql -Raw |
--     wsl -d pglab -u root -- docker exec -i platform-pg-dev psql -U platform_owner -d platform

BEGIN;

SELECT set_config('app.tenant_id', 'aaaaaaaa-0000-0000-0000-000000000001', true);

-- 让合成部门的任职、岗位、汇报线与老板角色早于数据窗口生效，
-- 否则 11-13 才生效的组织关系与 10 月工时记录自相矛盾。
UPDATE organization_employment SET effective_from = DATE '2026-06-01'
 WHERE tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001'
   AND id IN ('11111111-0000-4000-8000-000000000045',
              '11111111-0000-4000-8000-000000000046',
              '11111111-0000-4000-8000-000000000047')
   AND effective_from > DATE '2026-10-26';
UPDATE organization_employment_assignment SET effective_from = DATE '2026-06-01'
 WHERE tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001'
   AND employment_id IN ('11111111-0000-4000-8000-000000000045',
                         '11111111-0000-4000-8000-000000000046',
                         '11111111-0000-4000-8000-000000000047')
   AND effective_from > DATE '2026-10-26';
UPDATE organization_reporting_line SET effective_from = DATE '2026-06-01'
 WHERE tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001'
   AND id IN ('11111111-0000-4000-8000-000000000055',
              '11111111-0000-4000-8000-000000000056',
              '11111111-0000-4000-8000-000000000057')
   AND effective_from > DATE '2026-10-26';
UPDATE organization_owner_role SET effective_from = DATE '2026-06-01'
 WHERE tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001'
   AND id = '11111111-0000-4000-8000-000000000061'
   AND effective_from > DATE '2026-10-26';

CREATE TEMP TABLE demo_worklog_skip (person_id uuid, work_date date) ON COMMIT DROP;
INSERT INTO demo_worklog_skip (person_id, work_date) VALUES
 ('11111111-0000-4000-8000-000000000031', DATE '2026-11-03'),
 ('11111111-0000-4000-8000-000000000033', DATE '2026-10-29'),
 ('11111111-0000-4000-8000-000000000033', DATE '2026-11-05'),
 ('11111111-0000-4000-8000-000000000036', DATE '2026-10-30'),
 ('11111111-0000-4000-8000-000000000033', DATE '2026-11-10');

-- ------------------------------------------------------------------ 工时条目与日快照

DO $$
DECLARE
    v_tenant      uuid := 'aaaaaaaa-0000-0000-0000-000000000001';
    v_start       date := DATE '2026-10-26';
    v_end         date := DATE '2026-11-12';
    v_project     uuid := '11111111-0000-4000-8000-000000000071';
    v_times       time[] := ARRAY[time '09:00', time '09:30', time '13:30',
                                  time '15:30', time '17:00'];
    p             record;
    d             date;
    v_seq         int;
    v_min         int[];
    v_start_at    timestamptz;
    v_end_at      timestamptz;
    v_state       text;
    v_submitted   timestamptz;
    v_reviewed    timestamptz;
    v_delay_h     int;
    v_body        text;
    v_total       int;
    v_sub_id      uuid;
    v_version     int;
BEGIN
    FOR p IN
        SELECT * FROM (VALUES
            ('11111111-0000-4000-8000-000000000031'::uuid,
             '11111111-0000-4000-8000-000000000041'::uuid,
             '11111111-0000-4000-8000-000000000034'::uuid,
             ARRAY['主持团队站会与当日排期', '处理客户升级问题与资源协调',
                   '评审下属方案与工时处理', '整理周报与跨部门对齐', '邮件与内部沟通']::text[]),
            ('11111111-0000-4000-8000-000000000032'::uuid,
             '11111111-0000-4000-8000-000000000042'::uuid,
             '11111111-0000-4000-8000-000000000031'::uuid,
             ARRAY['部门工作区需求梳理', '客户成功试点功能实现',
                   '联调与缺陷修复', '代码评审与单元测试', '文档补充与问题跟进']::text[]),
            ('11111111-0000-4000-8000-000000000033'::uuid,
             '11111111-0000-4000-8000-000000000043'::uuid,
             '11111111-0000-4000-8000-000000000031'::uuid,
             ARRAY['工时与周报数据核对', '经营报表配置与校对',
                   '跨部门数据口径对齐', '问题清单复盘', '内部沟通与邮件']::text[]),
            ('11111111-0000-4000-8000-000000000035'::uuid,
             '11111111-0000-4000-8000-000000000045'::uuid,
             '11111111-0000-4000-8000-000000000031'::uuid,
             ARRAY['客户配置实施', '数据导入与校验',
                   '测试用例执行', '问题回归与记录', '文档与培训材料']::text[]),
            ('11111111-0000-4000-8000-000000000036'::uuid,
             '11111111-0000-4000-8000-000000000046'::uuid,
             '11111111-0000-4000-8000-000000000031'::uuid,
             ARRAY['客户成功试点跟进', '兼岗市场活动支持',
                   '需求澄清与方案确认', '联调与验收', '问题复盘与沟通']::text[])
        ) AS t(person_id, employment_id, reviewer_id, descs)
    LOOP
        FOR d IN SELECT generate_series(v_start, v_end, interval '1 day')::date LOOP
            CONTINUE WHEN extract(isodow FROM d) > 5;
            CONTINUE WHEN EXISTS (
                SELECT 1 FROM demo_worklog_skip s
                 WHERE s.person_id = p.person_id AND s.work_date = d);

            v_min := ARRAY[30, 150, 120, 90, 60];
            IF extract(isodow FROM d) = 1 THEN
                v_min[2] := 180;
            END IF;
            IF extract(isodow FROM d) = 5 THEN
                v_min[5] := 30;
            END IF;

            FOR v_seq IN 1..5 LOOP
                v_start_at := (d + v_times[v_seq]) AT TIME ZONE 'Asia/Shanghai';
                v_end_at := v_start_at + make_interval(mins => v_min[v_seq]);
                INSERT INTO worklog_entry
                    (id, tenant_id, person_id, employment_id, work_date,
                     starts_at, ends_at, duration_minutes, project_id, task_id,
                     description, source_type, client_entry_id, draft_version)
                VALUES
                    (md5('demo-wl-e:' || p.person_id || ':' || d || ':' || v_seq)::uuid,
                     v_tenant, p.person_id, p.employment_id, d,
                     v_start_at, v_end_at, v_min[v_seq],
                     CASE WHEN v_seq BETWEEN 2 AND 4 THEN v_project END, NULL,
                     p.descs[v_seq], 'manual', 'demo-wl:' || d || ':' || v_seq, 1)
                ON CONFLICT (tenant_id, person_id, client_entry_id) DO NOTHING;
            END LOOP;

            SELECT '[' || string_agg(
                       jsonb_build_object(
                           'entryId', id, 'startsAt', starts_at, 'endsAt', ends_at,
                           'minutes', duration_minutes,
                           'projectId', project_id, 'taskId', task_id,
                           'description', description, 'clientEntryId', client_entry_id)::text,
                       ',' ORDER BY starts_at, created_at) || ']',
                   sum(duration_minutes)
              INTO v_body, v_total
              FROM worklog_entry
             WHERE tenant_id = v_tenant AND person_id = p.person_id
               AND work_date = d AND deleted_at IS NULL;

            v_state := 'approved';
            IF (p.person_id, d) IN (
                    ('11111111-0000-4000-8000-000000000032'::uuid, DATE '2026-10-28'),
                    ('11111111-0000-4000-8000-000000000033'::uuid, DATE '2026-10-30'),
                    ('11111111-0000-4000-8000-000000000036'::uuid, DATE '2026-11-04')) THEN
                v_state := 'returned_then_approved';
            ELSIF (p.person_id, d) IN (
                    ('11111111-0000-4000-8000-000000000033'::uuid, DATE '2026-11-06')) THEN
                v_state := 'returned';
            ELSIF (p.person_id, d) IN (
                    ('11111111-0000-4000-8000-000000000031'::uuid, DATE '2026-11-12'),
                    ('11111111-0000-4000-8000-000000000033'::uuid, DATE '2026-11-12'),
                    ('11111111-0000-4000-8000-000000000036'::uuid, DATE '2026-11-12')) THEN
                v_state := 'submitted';
            END IF;

            v_submitted := ((d + time '18:30') AT TIME ZONE 'Asia/Shanghai')
                + make_interval(mins => abs(hashtext(p.person_id::text || d::text || 's')) % 90);
            v_delay_h := 1 + abs(hashtext(p.person_id::text || d::text)) % 30;
            v_reviewed := v_submitted + make_interval(hours => v_delay_h);

            IF v_state = 'returned_then_approved' THEN
                INSERT INTO worklog_submission
                    (id, tenant_id, person_id, work_date, version, status, snapshot,
                     snapshot_hash, total_minutes, submitted_at, submitted_by_person_id,
                     reviewed_at, reviewed_by_person_id, review_comment)
                VALUES
                    (md5('demo-wl-s:' || p.person_id || ':' || d || ':1')::uuid, v_tenant,
                     p.person_id, d, 1, 'returned', v_body::jsonb,
                     encode(sha256(convert_to(v_body, 'UTF8')), 'hex'), v_total,
                     v_submitted, p.person_id, v_reviewed, p.reviewer_id,
                     '说明过于简略，请补充客户与产出')
                ON CONFLICT (id) DO NOTHING;

                INSERT INTO worklog_submission
                    (id, tenant_id, person_id, work_date, version, status, snapshot,
                     snapshot_hash, total_minutes, submitted_at, submitted_by_person_id,
                     reviewed_at, reviewed_by_person_id, review_comment)
                VALUES
                    (md5('demo-wl-s:' || p.person_id || ':' || d || ':2')::uuid, v_tenant,
                     p.person_id, d, 2, 'approved', v_body::jsonb,
                     encode(sha256(convert_to(v_body, 'UTF8')), 'hex'), v_total,
                     v_reviewed + make_interval(hours => 6), p.person_id,
                     v_reviewed + make_interval(hours => 11), p.reviewer_id,
                     '已补充说明')
                ON CONFLICT (id) DO NOTHING;

                v_sub_id := md5('demo-wl-s:' || p.person_id || ':' || d || ':2')::uuid;
                v_version := 2;
            ELSE
                INSERT INTO worklog_submission
                    (id, tenant_id, person_id, work_date, version, status, snapshot,
                     snapshot_hash, total_minutes, submitted_at, submitted_by_person_id,
                     reviewed_at, reviewed_by_person_id, review_comment)
                VALUES
                    (md5('demo-wl-s:' || p.person_id || ':' || d || ':1')::uuid, v_tenant,
                     p.person_id, d, 1, v_state, v_body::jsonb,
                     encode(sha256(convert_to(v_body, 'UTF8')), 'hex'), v_total,
                     v_submitted, p.person_id,
                     CASE WHEN v_state <> 'submitted' THEN v_reviewed END,
                     CASE WHEN v_state <> 'submitted' THEN p.reviewer_id END,
                     CASE WHEN v_state = 'returned'
                          THEN '当天缺少客户侧说明，请补齐后重新提交' END)
                ON CONFLICT (id) DO NOTHING;

                v_sub_id := md5('demo-wl-s:' || p.person_id || ':' || d || ':1')::uuid;
                v_version := 1;
            END IF;

            INSERT INTO worklog_day
                (tenant_id, person_id, work_date, state, draft_version,
                 current_submission_id, updated_at)
            VALUES
                (v_tenant, p.person_id, d,
                 CASE WHEN v_state = 'returned_then_approved' THEN 'approved' ELSE v_state END,
                 v_version, v_sub_id, coalesce(v_reviewed, v_submitted))
            ON CONFLICT (tenant_id, person_id, work_date) DO NOTHING;
        END LOOP;
    END LOOP;

    -- 今天（2026-11-13）主管本人的在录草稿，让「我的工作」页有当日数据
    INSERT INTO worklog_entry
        (id, tenant_id, person_id, employment_id, work_date, starts_at, ends_at,
         duration_minutes, project_id, task_id, description, source_type,
         client_entry_id, draft_version)
    VALUES
        (md5('demo-wl-e:11111111-0000-4000-8000-000000000031:2026-11-13:1')::uuid,
         v_tenant, '11111111-0000-4000-8000-000000000031',
         '11111111-0000-4000-8000-000000000041', DATE '2026-11-13',
         (DATE '2026-11-13' + time '09:00') AT TIME ZONE 'Asia/Shanghai',
         (DATE '2026-11-13' + time '09:30') AT TIME ZONE 'Asia/Shanghai',
         30, NULL, NULL, '主持团队站会与当日排期', 'manual', 'demo-wl:2026-11-13:1', 1),
        (md5('demo-wl-e:11111111-0000-4000-8000-000000000031:2026-11-13:2')::uuid,
         v_tenant, '11111111-0000-4000-8000-000000000031',
         '11111111-0000-4000-8000-000000000041', DATE '2026-11-13',
         (DATE '2026-11-13' + time '09:30') AT TIME ZONE 'Asia/Shanghai',
         (DATE '2026-11-13' + time '12:00') AT TIME ZONE 'Asia/Shanghai',
         150, v_project, NULL, '处理客户升级问题与资源协调', 'manual',
         'demo-wl:2026-11-13:2', 1)
    ON CONFLICT (tenant_id, person_id, client_entry_id) DO NOTHING;
END $$;

-- ------------------------------------------------------------------ 周报

DO $$
DECLARE
    v_tenant  uuid := 'aaaaaaaa-0000-0000-0000-000000000001';
    w         record;
    p         record;
    v_status  text;
    v_version int;
    v_id      uuid;
    v_content jsonb;
    v_reviewed timestamptz;
BEGIN
    FOR w IN
        SELECT * FROM (VALUES (DATE '2026-10-26'), (DATE '2026-11-02'),
                              (DATE '2026-11-09')) AS t(week_start)
    LOOP
        FOR p IN
            SELECT * FROM (VALUES
                ('11111111-0000-4000-8000-000000000031'::uuid,
                 '11111111-0000-4000-8000-000000000034'::uuid),
                ('11111111-0000-4000-8000-000000000032'::uuid,
                 '11111111-0000-4000-8000-000000000031'::uuid),
                ('11111111-0000-4000-8000-000000000033'::uuid,
                 '11111111-0000-4000-8000-000000000031'::uuid),
                ('11111111-0000-4000-8000-000000000035'::uuid,
                 '11111111-0000-4000-8000-000000000031'::uuid),
                ('11111111-0000-4000-8000-000000000036'::uuid,
                 '11111111-0000-4000-8000-000000000031'::uuid)
            ) AS t(person_id, reviewer_id)
        LOOP
            v_status := 'approved';
            IF w.week_start = DATE '2026-11-09' THEN
                v_status := CASE p.person_id
                    WHEN '11111111-0000-4000-8000-000000000031'::uuid THEN 'draft'
                    WHEN '11111111-0000-4000-8000-000000000032'::uuid THEN 'submitted'
                    ELSE 'none' END;
            END IF;
            CONTINUE WHEN v_status = 'none';

            SELECT jsonb_build_object(
                       'weekStart', w.week_start::text,
                       'weekEnd', (w.week_start + 6)::text,
                       'days', coalesce((
                           SELECT jsonb_agg(jsonb_build_object(
                                      'date', l.work_date::text,
                                      'minutes', l.total_minutes,
                                      'submissionId', l.id::text) ORDER BY l.work_date)
                             FROM (SELECT DISTINCT ON (work_date) id, work_date, total_minutes
                                     FROM worklog_submission
                                    WHERE tenant_id = v_tenant AND person_id = p.person_id
                                      AND work_date BETWEEN w.week_start AND w.week_start + 6
                                      AND status IN ('submitted', 'approved')
                                    ORDER BY work_date, version DESC) l), '[]'::jsonb),
                       'items', coalesce((
                           SELECT jsonb_agg(jsonb_build_object('key', k, 'minutes', m)
                                            ORDER BY k)
                             FROM (SELECT CASE WHEN e->>'projectId' IS NULL THEN 'unassigned'
                                               ELSE 'project:' || (e->>'projectId') END AS k,
                                          sum((e->>'minutes')::int) AS m
                                     FROM (SELECT DISTINCT ON (work_date) id, snapshot
                                             FROM worklog_submission
                                            WHERE tenant_id = v_tenant
                                              AND person_id = p.person_id
                                              AND work_date BETWEEN w.week_start
                                                                AND w.week_start + 6
                                              AND status IN ('submitted', 'approved')
                                            ORDER BY work_date, version DESC) s,
                                          jsonb_array_elements(s.snapshot) AS e
                                    GROUP BY 1) agg), '[]'::jsonb),
                       'issues', CASE WHEN w.week_start = DATE '2026-11-02'
                                      THEN '客户侧接口联调排期偏紧，本周两次等待外部确认。'
                                      ELSE '' END,
                       'nextWeekPlan', CASE WHEN w.week_start = DATE '2026-11-02'
                                            THEN '下周完成试点范围确认并补齐回归用例。'
                                            ELSE '' END)
              INTO v_content;

            v_reviewed := ((w.week_start + 5) + time '19:00') AT TIME ZONE 'Asia/Shanghai'
                + make_interval(hours => 14);

            IF p.person_id = '11111111-0000-4000-8000-000000000033'::uuid
               AND w.week_start = DATE '2026-11-02' THEN
                INSERT INTO worklog_weekly_report
                    (id, tenant_id, person_id, week_start, week_end, version, status,
                     content, snapshot_hash, generated_at, submitted_at,
                     reviewed_at, reviewed_by_person_id, review_comment)
                VALUES
                    (md5('demo-wl-r:' || p.person_id || ':' || w.week_start || ':1')::uuid,
                     v_tenant, p.person_id, w.week_start, w.week_start + 6, 1, 'returned',
                     v_content, encode(sha256(convert_to(v_content::text, 'UTF8')), 'hex'),
                     v_reviewed - make_interval(hours => 20),
                     v_reviewed - make_interval(hours => 18), v_reviewed, p.reviewer_id,
                     '周报没有说明延期原因，请补充后重新提交')
                ON CONFLICT (id) DO NOTHING;

                INSERT INTO worklog_weekly_report
                    (id, tenant_id, person_id, week_start, week_end, version, status,
                     content, snapshot_hash, generated_at, submitted_at,
                     reviewed_at, reviewed_by_person_id, review_comment)
                VALUES
                    (md5('demo-wl-r:' || p.person_id || ':' || w.week_start || ':2')::uuid,
                     v_tenant, p.person_id, w.week_start, w.week_start + 6, 2, 'approved',
                     v_content, encode(sha256(convert_to(v_content::text, 'UTF8')), 'hex'),
                     v_reviewed - make_interval(hours => 8),
                     v_reviewed - make_interval(hours => 6),
                     v_reviewed + make_interval(hours => 4), p.reviewer_id, '已补充')
                ON CONFLICT (id) DO NOTHING;
            ELSE
                INSERT INTO worklog_weekly_report
                    (id, tenant_id, person_id, week_start, week_end, version, status,
                     content, snapshot_hash, generated_at, submitted_at,
                     reviewed_at, reviewed_by_person_id, review_comment)
                VALUES
                    (md5('demo-wl-r:' || p.person_id || ':' || w.week_start || ':1')::uuid,
                     v_tenant, p.person_id, w.week_start, w.week_start + 6, 1, v_status,
                     v_content,
                     CASE WHEN v_status <> 'draft'
                          THEN encode(sha256(convert_to(v_content::text, 'UTF8')), 'hex') END,
                     v_reviewed - make_interval(hours => 20),
                     CASE WHEN v_status <> 'draft'
                          THEN v_reviewed - make_interval(hours => 18) END,
                     CASE WHEN v_status = 'approved' THEN v_reviewed END,
                     CASE WHEN v_status = 'approved' THEN p.reviewer_id END,
                     NULL)
                ON CONFLICT (id) DO NOTHING;
            END IF;

            v_id := md5('demo-wl-r:' || p.person_id || ':' || w.week_start ||
                        CASE WHEN p.person_id = '11111111-0000-4000-8000-000000000033'::uuid
                                  AND w.week_start = DATE '2026-11-02'
                             THEN ':2' ELSE ':1' END)::uuid;

            IF v_status <> 'draft' THEN
                INSERT INTO worklog_report_source
                    (tenant_id, report_id, submission_id, item_key, minutes)
                SELECT v_tenant, v_id, l.id, 'total', l.total_minutes
                  FROM (SELECT DISTINCT ON (work_date) id, total_minutes
                          FROM worklog_submission
                         WHERE tenant_id = v_tenant AND person_id = p.person_id
                           AND work_date BETWEEN w.week_start AND w.week_start + 6
                           AND status IN ('submitted', 'approved')
                         ORDER BY work_date, version DESC) l
                ON CONFLICT DO NOTHING;
            END IF;
        END LOOP;
    END LOOP;
END $$;

COMMIT;
