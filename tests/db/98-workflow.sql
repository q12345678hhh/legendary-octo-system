-- WP03-06：通用工作流与审批数据库断言。以运行期账号 platform_app 执行，失败即 RAISE EXCEPTION。
--
-- 重点验证：
--   1. 一 key 一 published、发布后 schema 不可改；
--   2. business_key 幂等、直连任务按业务对象去重；
--   3. 状态流转日志只追加；
--   4. 状态与异常码枚举约束；
--   5. 8 张工作流表全部启用并强制 RLS，跨租户不可见。

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

\set wf_a '''99999999-0000-4000-8000-000000000001'''
\set wf_b '''99999999-0000-4000-8000-000000000002'''
\set wf_def '''99999999-0000-4000-8000-000000000101'''
\set wf_instance '''99999999-0000-4000-8000-000000000201'''
\set wf_token '''99999999-0000-4000-8000-000000000301'''

BEGIN;
SELECT set_config('app.tenant_id', :wf_a, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:wf_a, 'wf-a', '工作流甲', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;

DELETE FROM workflow_transition_log WHERE tenant_id = :wf_a;
DELETE FROM workflow_exception WHERE tenant_id = :wf_a;
DELETE FROM workflow_timer WHERE tenant_id = :wf_a;
DELETE FROM workflow_service_task WHERE tenant_id = :wf_a;
DELETE FROM workflow_task WHERE tenant_id = :wf_a;
DELETE FROM workflow_token WHERE tenant_id = :wf_a;
DELETE FROM workflow_instance WHERE tenant_id = :wf_a;
DELETE FROM workflow_definition WHERE tenant_id = :wf_a;

-- 1. 已发布定义、实例、令牌、任务与日志
INSERT INTO workflow_definition
    (id, tenant_id, key, version, name, status, schema, schema_hash, created_by, published_at)
VALUES (:wf_def, :wf_a, 'demo.leave', 1, '请假审批', 'published',
        '{"nodes":[{"key":"start","type":"start"},{"key":"end","type":"end"}],
          "edges":[{"from":"start","to":"end"}]}'::jsonb,
        'hash-v1', 'tester', now());

INSERT INTO workflow_instance
    (id, tenant_id, definition_id, definition_version, definition_key, business_type,
     business_key, status, started_by_person_id)
VALUES (:wf_instance, :wf_a, :wf_def, 1, 'demo.leave', 'generic', 'bk-1', 'running',
        '99999999-0000-4000-8000-000000000401');

INSERT INTO workflow_token (id, tenant_id, instance_id, node_key, status)
VALUES (:wf_token, :wf_a, :wf_instance, 'approve', 'waiting');

INSERT INTO workflow_task
    (tenant_id, instance_id, token_id, node_key, business_type, task_type, title,
     assignee_person_id, assignee_kind, status)
VALUES (:wf_a, :wf_instance, :wf_token, 'approve', 'generic', 'approval', '请假审批',
        '99999999-0000-4000-8000-000000000402', 'person', 'pending');

INSERT INTO workflow_transition_log (tenant_id, instance_id, node_key, action)
VALUES (:wf_a, :wf_instance, 'start', 'start');
COMMIT;

-- 2. 一 key 一 published：再插一条 published 必须唯一冲突
BEGIN;
SELECT set_config('app.tenant_id', :wf_a, true);
DO $$
BEGIN
    BEGIN
        INSERT INTO workflow_definition
            (tenant_id, key, version, name, status, schema, schema_hash, created_by)
        VALUES ('99999999-0000-4000-8000-000000000001', 'demo.leave', 2, '重复发布',
                'published', '{}'::jsonb, 'hash-v2', 'tester');
        RAISE EXCEPTION '同一 key 只允许一个 published 版本';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 3. 发布后 schema 不可改
BEGIN;
SELECT set_config('app.tenant_id', :wf_a, true);
DO $$
BEGIN
    BEGIN
        UPDATE workflow_definition SET schema = '{}'::jsonb
         WHERE id = '99999999-0000-4000-8000-000000000101';
    EXCEPTION WHEN raise_exception THEN
        NULL;
    END;

    IF (SELECT schema_hash FROM workflow_definition
         WHERE id = '99999999-0000-4000-8000-000000000101') <> 'hash-v1' THEN
        RAISE EXCEPTION '已发布定义不可修改 schema';
    END IF;
END
$$;
COMMIT;

-- 4. business_key 幂等：同租户同业务键唯一
BEGIN;
SELECT set_config('app.tenant_id', :wf_a, true);
DO $$
BEGIN
    BEGIN
        INSERT INTO workflow_instance
            (tenant_id, definition_id, definition_version, definition_key, business_type,
             business_key, started_by_person_id)
        VALUES ('99999999-0000-4000-8000-000000000001',
                '99999999-0000-4000-8000-000000000101', 1, 'demo.leave', 'generic', 'bk-1',
                '99999999-0000-4000-8000-000000000401');
        RAISE EXCEPTION 'business_key 必须唯一';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 5. 直连任务按业务对象去重（instance_id 为空）
BEGIN;
SELECT set_config('app.tenant_id', :wf_a, true);
DO $$
BEGIN
    INSERT INTO workflow_task
        (tenant_id, business_type, business_id, task_type, title, assignee_person_id,
         assignee_kind)
    VALUES ('99999999-0000-4000-8000-000000000001', 'worklog.time',
            '99999999-0000-4000-8000-000000000501', 'review', '工时待审核',
            '99999999-0000-4000-8000-000000000402', 'direct');

    BEGIN
        INSERT INTO workflow_task
            (tenant_id, business_type, business_id, task_type, title, assignee_person_id,
             assignee_kind)
        VALUES ('99999999-0000-4000-8000-000000000001', 'worklog.time',
                '99999999-0000-4000-8000-000000000501', 'review', '工时待审核',
                '99999999-0000-4000-8000-000000000402', 'direct');
        RAISE EXCEPTION '直连任务必须按业务对象去重';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 6. 状态流转日志只追加
BEGIN;
SELECT set_config('app.tenant_id', :wf_a, true);
DO $$
BEGIN
    BEGIN
        UPDATE workflow_transition_log SET comment = '篡改'
         WHERE tenant_id = '99999999-0000-4000-8000-000000000001';
    EXCEPTION WHEN raise_exception THEN
        NULL;
    END;

    IF EXISTS (SELECT 1 FROM workflow_transition_log
                WHERE tenant_id = '99999999-0000-4000-8000-000000000001'
                  AND comment = '篡改') THEN
        RAISE EXCEPTION '状态日志不允许更新';
    END IF;

    BEGIN
        DELETE FROM workflow_transition_log
         WHERE tenant_id = '99999999-0000-4000-8000-000000000001';
    EXCEPTION WHEN raise_exception THEN
        NULL;
    END;

    IF (SELECT count(*) FROM workflow_transition_log
         WHERE tenant_id = '99999999-0000-4000-8000-000000000001') = 0 THEN
        RAISE EXCEPTION '状态日志不允许删除';
    END IF;
END
$$;
COMMIT;

-- 7. 枚举约束：非法任务状态与非法异常码被拒绝
BEGIN;
SELECT set_config('app.tenant_id', :wf_a, true);
DO $$
BEGIN
    BEGIN
        INSERT INTO workflow_task
            (tenant_id, business_type, task_type, title, assignee_kind, status)
        VALUES ('99999999-0000-4000-8000-000000000001', 'generic', 'approval', '非法状态',
                'person', 'approved');
        RAISE EXCEPTION '非法任务状态应被拒绝';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO workflow_exception (tenant_id, code, message)
        VALUES ('99999999-0000-4000-8000-000000000001', 'UNKNOWN_CODE', '非法异常码');
        RAISE EXCEPTION '非法异常码应被拒绝';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 8. 跨租户隔离
BEGIN;
SELECT set_config('app.tenant_id', :wf_b, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:wf_b, 'wf-b', '工作流乙', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;

DO $$
DECLARE
    visible int;
BEGIN
    SELECT count(*) INTO visible FROM workflow_definition
     WHERE tenant_id = '99999999-0000-4000-8000-000000000001';
    IF visible <> 0 THEN
        RAISE EXCEPTION '跨租户可见流程定义：%', visible;
    END IF;

    SELECT count(*) INTO visible FROM workflow_instance
     WHERE tenant_id = '99999999-0000-4000-8000-000000000001';
    IF visible <> 0 THEN
        RAISE EXCEPTION '跨租户可见流程实例：%', visible;
    END IF;

    SELECT count(*) INTO visible FROM workflow_task
     WHERE tenant_id = '99999999-0000-4000-8000-000000000001';
    IF visible <> 0 THEN
        RAISE EXCEPTION '跨租户可见审批任务：%', visible;
    END IF;
END
$$;
COMMIT;

-- 9. RLS 开关与策略数量
DO $$
DECLARE
    tbl text;
    flags record;
    policies int;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'workflow_definition', 'workflow_instance', 'workflow_token', 'workflow_task',
        'workflow_transition_log', 'workflow_timer', 'workflow_service_task',
        'workflow_exception'] LOOP
        SELECT c.relrowsecurity, c.relforcerowsecurity INTO flags
          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = tbl;
        IF NOT FOUND THEN
            RAISE EXCEPTION '缺少工作流表：%', tbl;
        END IF;
        IF NOT flags.relrowsecurity OR NOT flags.relforcerowsecurity THEN
            RAISE EXCEPTION '% 必须启用并强制 RLS', tbl;
        END IF;

        SELECT count(*) INTO policies FROM pg_policies WHERE tablename = tbl;
        IF policies <> 1 THEN
            RAISE EXCEPTION '% 应恰好有 1 条策略，实际 %', tbl, policies;
        END IF;
    END LOOP;
END
$$;

\echo 'WP03-06 工作流数据库断言通过'
