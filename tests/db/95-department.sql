-- WP03-01：部门工作区数据库断言。以运行期账号 platform_app 执行，失败即 RAISE EXCEPTION。
--
-- 重点验证：
--   1. 组织单元插入/改名/归档自动同步部门空间，历史行不删除；
--   2. 部门设置按租户隔离，非法星期被数据库拒绝；
--   3. 部门空间与设置均启用并强制 RLS，跨租户读写不可见；
--   4. Phase 3 权限码已登记，敏感标记符合预期。

DO $$
BEGIN
    IF current_setting('is_superuser') <> 'off' THEN
        RAISE EXCEPTION '用例身份错误：platform_app 不能是超级用户';
    END IF;
    IF current_setting('row_security') <> 'on' THEN
        RAISE EXCEPTION 'row_security 必须为 on，实际 %', current_setting('row_security');
    END IF;
    IF (SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION '运行账号不能带 BYPASSRLS';
    END IF;
END
$$;

\set ws_a '''dddddddd-0000-4000-8000-000000000007'''
\set ws_b '''cccccccc-0000-4000-8000-000000000003'''

-- 会话级租户上下文：后续独立 DO 块默认读取工作区甲，步骤 4 再临时切到乙
SELECT set_config('app.tenant_id', :ws_a, false);

BEGIN;
SELECT set_config('app.tenant_id', :ws_a, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:ws_a, 'workspace-a', '工作区甲', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;

-- 幂等：先清掉本租户的空间，再让组织单元触发器重建
DELETE FROM department_space WHERE tenant_id = :ws_a;

INSERT INTO organization_legal_entity (id, tenant_id, code, name, registration_no)
VALUES ('dddddddd-0000-4000-8000-000000000101', :ws_a, 'LE-WS', '工作区甲公司', '91310000WS0001')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_unit (id, tenant_id, parent_id, legal_entity_id, code, name, unit_type, sort_order)
VALUES ('dddddddd-0000-4000-8000-000000000201', :ws_a, NULL,
        'dddddddd-0000-4000-8000-000000000101', 'U-WS-ROOT', '工作区总部', 'company', 0),
       ('dddddddd-0000-4000-8000-000000000202', :ws_a, 'dddddddd-0000-4000-8000-000000000201',
        'dddddddd-0000-4000-8000-000000000101', 'U-WS-RD', '研发部', 'department', 1),
       ('dddddddd-0000-4000-8000-000000000203', :ws_a, 'dddddddd-0000-4000-8000-000000000202',
        'dddddddd-0000-4000-8000-000000000101', 'U-WS-PLAT', '平台组', 'team', 2)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status;
COMMIT;

-- 1. 触发器自动建空间，且与组织单元一一对应
DO $$
DECLARE
    unit_count int;
    space_count int;
    rd_name text;
    rd_status text;
BEGIN
    SELECT count(*) INTO unit_count
      FROM organization_unit
     WHERE tenant_id = 'dddddddd-0000-4000-8000-000000000007';
    SELECT count(*) INTO space_count
      FROM department_space
     WHERE tenant_id = 'dddddddd-0000-4000-8000-000000000007';
    IF unit_count <> 3 OR space_count <> 3 THEN
        RAISE EXCEPTION '空间未随组织单元自动创建：unit=%, space=%', unit_count, space_count;
    END IF;

    SELECT name, status INTO rd_name, rd_status
      FROM department_space
     WHERE tenant_id = 'dddddddd-0000-4000-8000-000000000007'
       AND org_unit_id = 'dddddddd-0000-4000-8000-000000000202';
    IF rd_name <> '研发部' OR rd_status <> 'active' THEN
        RAISE EXCEPTION '空间名称/状态未同步：name=%, status=%', rd_name, rd_status;
    END IF;
END
$$;

-- 2. 改名与归档同步，且只改状态不删除
BEGIN;
SELECT set_config('app.tenant_id', :ws_a, true);
UPDATE organization_unit
   SET name = '研发中心', status = 'archived'
 WHERE id = 'dddddddd-0000-4000-8000-000000000203';
COMMIT;

DO $$
DECLARE
    v_name text;
    v_status text;
    v_archived_at timestamptz;
BEGIN
    SELECT ds.name, ds.status, ds.archived_at INTO v_name, v_status, v_archived_at
      FROM department_space ds
     WHERE ds.tenant_id = 'dddddddd-0000-4000-8000-000000000007'
       AND ds.org_unit_id = 'dddddddd-0000-4000-8000-000000000203';
    IF v_name <> '研发中心' OR v_status <> 'archived' OR v_archived_at IS NULL THEN
        RAISE EXCEPTION '归档同步失败：name=%, status=%, archived_at=%', v_name, v_status, v_archived_at;
    END IF;
END
$$;

-- 3. 设置默认值与写入；非法星期由数据库拒绝
DO $$
DECLARE
    day_end time;
    weekday int;
BEGIN
    SELECT day_end_time, week_report_due_weekday INTO day_end, weekday
      FROM department_space_setting
     WHERE tenant_id = 'dddddddd-0000-4000-8000-000000000007'
       AND org_unit_id = 'dddddddd-0000-4000-8000-000000000202';

    IF day_end IS NOT NULL THEN
        RAISE EXCEPTION '未配置时不应存在设置行';
    END IF;

    INSERT INTO department_space_setting
        (tenant_id, org_unit_id, day_end_time, week_report_due_weekday, updated_by)
    VALUES ('dddddddd-0000-4000-8000-000000000007',
            'dddddddd-0000-4000-8000-000000000202', '17:30', 4, 'test')
    ON CONFLICT (tenant_id, org_unit_id) DO UPDATE
       SET day_end_time = EXCLUDED.day_end_time,
           week_report_due_weekday = EXCLUDED.week_report_due_weekday;

    BEGIN
        INSERT INTO department_space_setting
            (tenant_id, org_unit_id, day_end_time, week_report_due_weekday, updated_by)
        VALUES ('dddddddd-0000-4000-8000-000000000007',
                'dddddddd-0000-4000-8000-000000000202', '17:30', 8, 'test')
        ON CONFLICT (tenant_id, org_unit_id) DO UPDATE
           SET week_report_due_weekday = EXCLUDED.week_report_due_weekday;
        RAISE EXCEPTION '非法星期应被拒绝';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END
$$;

-- 4. 跨租户隔离：切到租户乙后不可见
BEGIN;
SELECT set_config('app.tenant_id', :ws_b, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:ws_b, 'workspace-b', '工作区乙', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;

DO $$
DECLARE
    visible int;
BEGIN
    SELECT count(*) INTO visible FROM department_space
     WHERE org_unit_id = 'dddddddd-0000-4000-8000-000000000202';
    IF visible <> 0 THEN
        RAISE EXCEPTION '跨租户可见部门空间：%', visible;
    END IF;
END
$$;
COMMIT;

-- 5. RLS 开关
DO $$
DECLARE
    tbl text;
    flags record;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['department_space', 'department_space_setting'] LOOP
        SELECT c.relrowsecurity, c.relforcerowsecurity INTO flags
          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = tbl;
        IF NOT FOUND THEN
            RAISE EXCEPTION '缺少部门表：%', tbl;
        END IF;
        IF NOT flags.relrowsecurity OR NOT flags.relforcerowsecurity THEN
            RAISE EXCEPTION '% 必须启用并强制 RLS', tbl;
        END IF;
    END LOOP;
END
$$;

-- 6. 权限码登记
DO $$
DECLARE
    missing int;
    sensitive_ok boolean;
BEGIN
    SELECT count(*) INTO missing
      FROM (VALUES
            ('department.workspace.read'), ('department.workspace.manage'),
            ('worklog.time.read'), ('worklog.time.write'), ('worklog.time.review'),
            ('worklog.time.export'), ('worklog.report.read'), ('worklog.report.submit'),
            ('worklog.report.review'), ('insight.work.read'), ('insight.work.export'),
            ('collaboration.channel.read'), ('collaboration.channel.manage'),
            ('collaboration.post.write'), ('collaboration.task.write'),
            ('collaboration.page.manage'), ('workflow.definition.manage'),
            ('workflow.instance.start'), ('workflow.task.act'),
            ('workflow.instance.admin'), ('appcatalog.module.read'),
            ('appcatalog.suggestion.create'), ('appcatalog.suggestion.review'),
            ('appcatalog.module.manage')
           ) AS expected(code)
     WHERE NOT EXISTS (
           SELECT 1 FROM authorization_permission p WHERE p.code = expected.code);

    IF missing <> 0 THEN
        RAISE EXCEPTION '缺少 Phase 3 权限码：%', missing;
    END IF;

    SELECT sensitive INTO sensitive_ok
      FROM authorization_permission WHERE code = 'department.workspace.manage';
    IF sensitive_ok IS NOT TRUE THEN
        RAISE EXCEPTION 'department.workspace.manage 必须标记为敏感';
    END IF;
END
$$;

\echo 'WP03-01 部门工作区数据库断言通过'
