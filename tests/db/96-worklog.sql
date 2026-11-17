-- WP03-05：手工工时与周报数据库断言（运行期账号 platform_app）。
--
-- 重点验证：
--   1. 单条时长、来源、客户端幂等键约束；
--   2. 日快照不可改删，退回/审核元数据仍可更新；
--   3. 周报提交后内容冻结，草稿可改；
--   4. 设置阈值下限为 5，跨租户不可见；
--   5. 回收站/留置的数据类已扩展到工时与周报。

DO $$
BEGIN
    IF current_setting('is_superuser') <> 'off' THEN
        RAISE EXCEPTION '用例身份错误：platform_app 不能是超级用户';
    END IF;
    IF current_setting('row_security') <> 'on' THEN
        RAISE EXCEPTION 'row_security 必须为 on';
    END IF;
END
$$;

\set wl_a '''dddddddd-0000-4000-8000-000000000008'''
\set wl_b '''cccccccc-0000-4000-8000-000000000004'''
\set wl_person '''dddddddd-0000-4000-8000-000000000301'''
\set wl_reviewer '''dddddddd-0000-4000-8000-000000000302'''

SELECT set_config('app.tenant_id', :wl_a, false);

BEGIN;
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:wl_a, 'worklog-a', '工时租户甲', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;

DELETE FROM worklog_report_source WHERE report_id IN (
    SELECT id FROM worklog_weekly_report WHERE tenant_id = :wl_a);
DELETE FROM worklog_day WHERE tenant_id = :wl_a;
DELETE FROM worklog_submission WHERE tenant_id = :wl_a;
DELETE FROM worklog_weekly_report WHERE tenant_id = :wl_a;
DELETE FROM worklog_entry WHERE tenant_id = :wl_a;

INSERT INTO organization_person (id, tenant_id, display_name, status)
VALUES (:wl_person, :wl_a, '工时员工', 'active'),
       (:wl_reviewer, :wl_a, '审核主管', 'active')
ON CONFLICT (id) DO NOTHING;

INSERT INTO worklog_entry
    (tenant_id, person_id, work_date, duration_minutes, description, client_entry_id)
VALUES (:wl_a, :wl_person, current_date - 1, 120, '完成部门首屏接口', 'seed-1'),
       (:wl_a, :wl_person, current_date - 1, 90, '评审权限模型', 'seed-2');
COMMIT;

-- 1. 约束
DO $$
BEGIN
    BEGIN
        INSERT INTO worklog_entry
            (tenant_id, person_id, work_date, duration_minutes, description, client_entry_id)
        VALUES ('dddddddd-0000-4000-8000-000000000008',
                'dddddddd-0000-4000-8000-000000000301', current_date, 0, '零时长', 'bad-1');
        RAISE EXCEPTION '0 分钟应被拒绝';
    EXCEPTION WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO worklog_entry
            (tenant_id, person_id, work_date, duration_minutes, description, source_type,
             client_entry_id)
        VALUES ('dddddddd-0000-4000-8000-000000000008',
                'dddddddd-0000-4000-8000-000000000301', current_date, 30, '非法来源', 'sensor',
                'bad-2');
        RAISE EXCEPTION '未知来源类型应被拒绝';
    EXCEPTION WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO worklog_entry
            (tenant_id, person_id, work_date, duration_minutes, description, client_entry_id)
        VALUES ('dddddddd-0000-4000-8000-000000000008',
                'dddddddd-0000-4000-8000-000000000301', current_date, 30, '重复', 'seed-1');
        RAISE EXCEPTION '同一客户端条目标识应被唯一约束拒绝';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
END
$$;

-- 2. 快照不可改删，但审核状态可更新
BEGIN;
INSERT INTO worklog_submission
    (tenant_id, person_id, work_date, version, snapshot, snapshot_hash, total_minutes,
     submitted_by_person_id)
VALUES (:wl_a, :wl_person, current_date - 1, 1, '[{"minutes":210}]'::jsonb, 'hash-1', 210,
        :wl_person);
COMMIT;

DO $$
BEGIN
    BEGIN
        UPDATE worklog_submission
           SET snapshot = '[{"minutes":1}]'::jsonb
         WHERE tenant_id = 'dddddddd-0000-4000-8000-000000000008';
        RAISE EXCEPTION '快照内容不应允许修改';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM NOT LIKE '%不可修改%' THEN RAISE; END IF;
    END;

    BEGIN
        DELETE FROM worklog_submission
         WHERE tenant_id = 'dddddddd-0000-4000-8000-000000000008';
        RAISE EXCEPTION '快照不应允许删除';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM NOT LIKE '%不允许删除%' THEN RAISE; END IF;
    END;
END
$$;

BEGIN;
UPDATE worklog_submission
   SET status = 'approved', reviewed_at = now(), reviewed_by_person_id = :wl_reviewer
 WHERE tenant_id = :wl_a AND version = 1;
COMMIT;

DO $$
DECLARE
    state text;
BEGIN
    SELECT status INTO state FROM worklog_submission
     WHERE tenant_id = 'dddddddd-0000-4000-8000-000000000008' AND version = 1;
    IF state <> 'approved' THEN
        RAISE EXCEPTION '审核状态应可更新，实际 %', state;
    END IF;
END
$$;

-- 3. 周报：草稿可改，提交后冻结
BEGIN;
INSERT INTO worklog_weekly_report
    (tenant_id, person_id, week_start, week_end, version, content)
VALUES (:wl_a, :wl_person, date_trunc('week', current_date)::date,
        date_trunc('week', current_date)::date + 6, 1,
        '{"days":[],"items":[],"issues":"","nextWeekPlan":""}'::jsonb);
COMMIT;

DO $$
DECLARE
    report_id uuid;
BEGIN
    SELECT id INTO report_id FROM worklog_weekly_report
     WHERE tenant_id = 'dddddddd-0000-4000-8000-000000000008';

    UPDATE worklog_weekly_report SET content = jsonb_set(content, '{issues}', '"草稿问题"')
     WHERE id = report_id;

    UPDATE worklog_weekly_report SET status = 'submitted', submitted_at = now()
     WHERE id = report_id;

    BEGIN
        UPDATE worklog_weekly_report SET content = jsonb_set(content, '{issues}', '"提交后修改"')
         WHERE id = report_id;
        RAISE EXCEPTION '已提交周报内容不应允许修改';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM NOT LIKE '%不可修改%' THEN RAISE; END IF;
    END;

    BEGIN
        DELETE FROM worklog_weekly_report WHERE id = report_id;
        RAISE EXCEPTION '已提交周报不应允许删除';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM NOT LIKE '%不允许删除%' THEN RAISE; END IF;
    END;
END
$$;

-- 4. 设置阈值下限与跨租户隔离
DO $$
BEGIN
    BEGIN
        INSERT INTO worklog_setting (tenant_id, min_group_size)
        VALUES ('dddddddd-0000-4000-8000-000000000008', 4);
        RAISE EXCEPTION '最小群组 4 应被拒绝';
    EXCEPTION WHEN check_violation THEN NULL;
    END;

    INSERT INTO worklog_setting (tenant_id, min_group_size)
    VALUES ('dddddddd-0000-4000-8000-000000000008', 8)
    ON CONFLICT (tenant_id) DO UPDATE SET min_group_size = 8;

    IF (SELECT min_group_size FROM worklog_setting
         WHERE tenant_id = 'dddddddd-0000-4000-8000-000000000008') <> 8 THEN
        RAISE EXCEPTION '最小群组应可提高到 8';
    END IF;
END
$$;

BEGIN;
SELECT set_config('app.tenant_id', :wl_b, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:wl_b, 'worklog-b', '工时租户乙', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;

DO $$
DECLARE
    visible int;
BEGIN
    SELECT count(*) INTO visible FROM worklog_entry;
    IF visible <> 0 THEN
        RAISE EXCEPTION '跨租户可见工时节条目：%', visible;
    END IF;
    SELECT count(*) INTO visible FROM worklog_submission;
    IF visible <> 0 THEN
        RAISE EXCEPTION '跨租户可见工时快照：%', visible;
    END IF;
END
$$;
COMMIT;

-- 5. 回收站/留置数据类
DO $$
DECLARE
    definition text;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO definition
      FROM pg_constraint WHERE conname = 'retention_bin_item_class_check';
    IF definition NOT LIKE '%worklog_entry%' THEN
        RAISE EXCEPTION '回收站未包含 worklog_entry：%', definition;
    END IF;

    SELECT pg_get_constraintdef(oid) INTO definition
      FROM pg_constraint WHERE conname = 'retention_legal_hold_class_check';
    IF definition NOT LIKE '%worklog_weekly_report%' THEN
        RAISE EXCEPTION '留置未包含 worklog_weekly_report：%', definition;
    END IF;
END
$$;

\echo 'WP03-05 工时与周报数据库断言通过'
