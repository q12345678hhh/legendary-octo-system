-- WP02-04：统一授权决策的数据库断言。
-- 以 platform_app 身份执行；租户操作都在显式事务内用 set_config(..., true) 建立上下文。

\set authz_e '''eeeeeeee-0000-4000-8000-000000000005'''
\set authz_f '''ffffffff-0000-4000-8000-000000000006'''

-- 1. 无租户上下文：租户授权表一行不可见；全局权限目录仍可读
DO $$
DECLARE
    hidden integer;
    catalog integer;
BEGIN
    SELECT count(*) INTO hidden FROM authorization_role;
    IF hidden <> 0 THEN
        RAISE EXCEPTION '无上下文时 authorization_role 应不可见，实际 % 行', hidden;
    END IF;
    SELECT count(*) INTO hidden FROM authorization_assignment;
    IF hidden <> 0 THEN
        RAISE EXCEPTION '无上下文时 authorization_assignment 应不可见，实际 % 行', hidden;
    END IF;
    SELECT count(*) INTO hidden FROM authorization_deny;
    IF hidden <> 0 THEN
        RAISE EXCEPTION '无上下文时 authorization_deny 应不可见，实际 % 行', hidden;
    END IF;
    SELECT count(*) INTO hidden FROM authorization_cache_version;
    IF hidden <> 0 THEN
        RAISE EXCEPTION '无上下文时 authorization_cache_version 应不可见，实际 % 行', hidden;
    END IF;
    SELECT count(*) INTO hidden FROM authorization_decision_log;
    IF hidden <> 0 THEN
        RAISE EXCEPTION '无上下文时 authorization_decision_log 应不可见，实际 % 行', hidden;
    END IF;

    SELECT count(*) INTO catalog FROM authorization_permission;
    IF catalog < 23 THEN
        RAISE EXCEPTION '全局权限目录应有至少 23 条，实际 %', catalog;
    END IF;
END
$$;

-- 2. 租户甲：主体、自定义角色、授权、显式拒绝、字段/条件策略与版本递增
BEGIN;
SELECT set_config('app.tenant_id', :authz_e, true);
DO $$
DECLARE
    v0 bigint;
    v1 bigint;
    v2 bigint;
    v3 bigint;
    v4 bigint;
    v5 bigint;
    v6 bigint;
    expired integer;
    denies integer;
BEGIN
    INSERT INTO identity_actor (id, tenant_id, actor_type, display_name)
    VALUES ('a0000000-0000-4000-8000-000000000001', 'eeeeeeee-0000-4000-8000-000000000005',
            'service', '权限测试主体甲'),
           ('a0000000-0000-4000-8000-000000000002', 'eeeeeeee-0000-4000-8000-000000000005',
            'service', '权限测试主体乙');

    INSERT INTO authorization_role (id, tenant_id, code, name, description)
    VALUES ('a1000000-0000-4000-8000-000000000001', 'eeeeeeee-0000-4000-8000-000000000005',
            'project-auditor', '项目审计员', '只读指定部门子树的人员目录');

    INSERT INTO authorization_role_permission (tenant_id, role_id, permission_code)
    VALUES ('eeeeeeee-0000-4000-8000-000000000005',
            'a1000000-0000-4000-8000-000000000001', 'directory.person.read'),
           ('eeeeeeee-0000-4000-8000-000000000005',
            'a1000000-0000-4000-8000-000000000001', 'directory.person.export');

    SELECT version INTO v0 FROM authorization_cache_version
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005';
    IF v0 IS NULL THEN
        RAISE EXCEPTION '角色权限写入后缓存版本行不存在';
    END IF;

    INSERT INTO authorization_assignment
        (id, tenant_id, actor_id, role_code, scope_type, scope_id, valid_from, reason, granted_by)
    VALUES ('a2000000-0000-4000-8000-000000000001', 'eeeeeeee-0000-4000-8000-000000000005',
            'a0000000-0000-4000-8000-000000000001', 'project-auditor', 'org_unit_subtree',
            'e0000000-0000-4000-8000-000000000202', now() - interval '1 day',
            '审计研发部子树', 'verify');

    SELECT version INTO v1 FROM authorization_cache_version
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005';
    IF v1 <> v0 + 1 THEN
        RAISE EXCEPTION '新增授权后版本应 +1，实际 % -> %', v0, v1;
    END IF;

    -- 委托：指向源授权，范围与到期不得放大（数据库只校验引用，业务不变量由服务层保证）
    INSERT INTO authorization_assignment
        (id, tenant_id, actor_id, role_code, scope_type, scope_id, valid_from, valid_to,
         reason, granted_by, delegated_from_id)
    VALUES ('a2000000-0000-4000-8000-000000000002', 'eeeeeeee-0000-4000-8000-000000000005',
            'a0000000-0000-4000-8000-000000000002', 'project-auditor', 'org_unit_subtree',
            'e0000000-0000-4000-8000-000000000202', now() - interval '1 day',
            now() + interval '30 days', '代理审计', 'verify',
            'a2000000-0000-4000-8000-000000000001');

    -- 临时授权：已到期，仍保留行但决策必须过滤
    INSERT INTO authorization_assignment
        (tenant_id, actor_id, role_code, scope_type, valid_from, valid_to, reason, granted_by)
    VALUES ('eeeeeeee-0000-4000-8000-000000000005',
            'a0000000-0000-4000-8000-000000000002', 'employee', 'self',
            now() - interval '10 days', now() - interval '1 day', '临时查看', 'verify');

    SELECT count(*) INTO expired FROM authorization_assignment
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005'
       AND actor_id = 'a0000000-0000-4000-8000-000000000002'
       AND valid_to <= now();
    IF expired <> 1 THEN
        RAISE EXCEPTION '应有 1 条已到期临时授权，实际 %', expired;
    END IF;

    INSERT INTO authorization_deny
        (tenant_id, role_code, permission_code, resource_type, reason, created_by)
    VALUES ('eeeeeeee-0000-4000-8000-000000000005', 'project-auditor',
            'directory.person.export', 'directory.person', '审计员禁止导出', 'verify');

    SELECT count(*) INTO denies FROM authorization_deny
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005';
    IF denies <> 1 THEN
        RAISE EXCEPTION '显式拒绝应为 1 条，实际 %', denies;
    END IF;

    INSERT INTO authorization_field_policy
        (tenant_id, role_code, resource_type, field_name, can_view, can_export, effect)
    VALUES ('eeeeeeee-0000-4000-8000-000000000005', 'project-auditor', 'directory.person',
            'primaryEmail', false, false, 'deny');

    INSERT INTO authorization_condition_policy
        (tenant_id, permission_code, name, condition, effect, priority)
    VALUES ('eeeeeeee-0000-4000-8000-000000000005', 'directory.person.read', '仅在职',
            '{"attribute":"employment_status","operator":"equals","value":"active"}'::jsonb,
            'allow', 10);

    SELECT version INTO v2 FROM authorization_cache_version
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005';
    IF v2 <= v1 THEN
        RAISE EXCEPTION '拒绝/字段/条件策略写入后版本未递增：% -> %', v1, v2;
    END IF;

    -- 撤销授权同样递增版本
    UPDATE authorization_assignment
       SET revoked_at = now(), revoked_by = 'verify', updated_at = now()
     WHERE id = 'a2000000-0000-4000-8000-000000000002';
    SELECT version INTO v3 FROM authorization_cache_version
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005';
    IF v3 <> v2 + 1 THEN
        RAISE EXCEPTION '撤销授权后版本应 +1，实际 % -> %', v2, v3;
    END IF;

    -- 组织关系变化（部门改名）同样递增版本
    UPDATE organization_unit SET name = name || '（授权测试）', updated_at = now()
     WHERE id = 'e0000000-0000-4000-8000-000000000202';
    SELECT version INTO v4 FROM authorization_cache_version
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005';
    IF v4 <> v3 + 1 THEN
        RAISE EXCEPTION '组织变更后版本应 +1，实际 % -> %', v3, v4;
    END IF;

    -- 成员变化（新增一条成员记录）同样递增版本
    INSERT INTO identity_user_account (id, tenant_id, email, email_normalized, display_name, status)
    VALUES ('a3000000-0000-4000-8000-000000000001', 'eeeeeeee-0000-4000-8000-000000000005',
            'authz@example.test', 'authz@example.test', '授权测试账号', 'active');
    INSERT INTO identity_tenant_member (tenant_id, user_id, person_id, status, is_owner, is_admin)
    VALUES ('eeeeeeee-0000-4000-8000-000000000005',
            'a3000000-0000-4000-8000-000000000001',
            'e0000000-0000-4000-8000-000000000401', 'active', false, false);
    SELECT version INTO v5 FROM authorization_cache_version
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005';
    IF v5 <> v4 + 1 THEN
        RAISE EXCEPTION '成员变化后版本应 +1，实际 % -> %', v4, v5;
    END IF;

    -- 决策日志只允许插入
    INSERT INTO authorization_decision_log
        (tenant_id, actor_id, permission_code, resource_type, allowed, reason_code, factors)
    VALUES ('eeeeeeee-0000-4000-8000-000000000005',
            'a0000000-0000-4000-8000-000000000001', 'directory.person.read',
            'directory.person', true, 'ALLOWED', '[]'::jsonb);
    SELECT version INTO v6 FROM authorization_cache_version
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005';
    IF v6 <> v5 THEN
        RAISE EXCEPTION '决策日志不应改变授权版本：% -> %', v5, v6;
    END IF;
END
$$;
COMMIT;

-- 3. 约束：跨租户主体、范围与 scope_id 不匹配、决策日志不可改删
BEGIN;
SELECT set_config('app.tenant_id', :authz_f, true);
INSERT INTO identity_actor (id, tenant_id, actor_type, display_name)
VALUES ('b0000000-0000-4000-8000-000000000001', 'ffffffff-0000-4000-8000-000000000006',
        'service', '权限测试主体丙');
COMMIT;

BEGIN;
SELECT set_config('app.tenant_id', :authz_e, true);
DO $$
BEGIN
    BEGIN
        INSERT INTO authorization_assignment
            (tenant_id, actor_id, role_code, scope_type, valid_from, reason, granted_by)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005',
                'b0000000-0000-4000-8000-000000000001', 'employee', 'tenant',
                now(), '跨租户主体', 'verify');
        RAISE EXCEPTION '跨租户主体竟然写入成功';
    EXCEPTION WHEN foreign_key_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO authorization_assignment
            (tenant_id, actor_id, role_code, scope_type, scope_id, valid_from, reason, granted_by)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005',
                'a0000000-0000-4000-8000-000000000001', 'employee', 'tenant',
                gen_random_uuid(), now(), 'tenant 范围不应带 scope_id', 'verify');
        RAISE EXCEPTION 'tenant 范围带 scope_id 竟然成功';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO authorization_assignment
            (tenant_id, actor_id, role_code, scope_type, valid_from, reason, granted_by)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005',
                'a0000000-0000-4000-8000-000000000001', 'employee', 'org_unit',
                now(), 'org_unit 范围缺少 scope_id', 'verify');
        RAISE EXCEPTION 'org_unit 缺少 scope_id 竟然成功';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        UPDATE authorization_decision_log SET allowed = false
         WHERE actor_id = 'a0000000-0000-4000-8000-000000000001';
        RAISE EXCEPTION '决策日志竟然可以被修改';
    EXCEPTION WHEN raise_exception THEN
        NULL;
    END;

    BEGIN
        DELETE FROM authorization_decision_log
         WHERE actor_id = 'a0000000-0000-4000-8000-000000000001';
        RAISE EXCEPTION '决策日志竟然可以被删除';
    EXCEPTION WHEN raise_exception THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 4. 跨租户隔离：租户乙看不到租户甲的授权、拒绝与版本
BEGIN;
SELECT set_config('app.tenant_id', :authz_f, true);
DO $$
DECLARE
    visible integer;
BEGIN
    SELECT count(*) INTO visible FROM authorization_assignment;
    IF visible <> 0 THEN
        RAISE EXCEPTION '租户乙应看不到租户甲授权，实际 % 行', visible;
    END IF;
    SELECT count(*) INTO visible FROM authorization_deny;
    IF visible <> 0 THEN
        RAISE EXCEPTION '租户乙应看不到租户甲拒绝，实际 % 行', visible;
    END IF;
    SELECT count(*) INTO visible FROM authorization_decision_log;
    IF visible <> 0 THEN
        RAISE EXCEPTION '租户乙应看不到租户甲决策日志，实际 % 行', visible;
    END IF;
END
$$;
COMMIT;

\echo 'WP02-04 统一授权数据库断言通过'
