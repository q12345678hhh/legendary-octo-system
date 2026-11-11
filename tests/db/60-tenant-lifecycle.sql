-- WP02-01：租户生命周期表在真实 PostgreSQL 上的 RLS、约束与跨租户编号断言。
-- 以 platform_app 身份执行；每个租户操作都在显式事务内用 set_config(..., true) 建立上下文。

\set lc_a '''aaaaaaaa-0000-4000-8000-000000000001'''
\set lc_b '''bbbbbbbb-0000-4000-8000-000000000002'''

-- 1. 两个租户可以拥有完全相同的业务编号
BEGIN;
SELECT set_config('app.tenant_id', :lc_a, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:lc_a, 'lc-alpha', '生命周期甲', 'active', 'standard');
INSERT INTO tenancy_profile (tenant_id, brand_name, brand_color, locale, timezone, features, updated_by)
VALUES (:lc_a, '生命周期甲', '#1f6feb', 'zh-CN', 'Asia/Shanghai', '{"ai": false}'::jsonb, 'verify');
INSERT INTO tenancy_domain (tenant_id, domain, kind, verified)
VALUES (:lc_a, 'lc-alpha.example.com', 'primary', true);
INSERT INTO tenancy_business_number (tenant_id, scope, business_number, sequence)
VALUES (:lc_a, 'WO', 'WO-000001', 1);
INSERT INTO tenancy_lifecycle_audit
    (tenant_id, action, from_status, to_status, actor, reason, correlation_id)
VALUES (:lc_a, 'created', NULL, 'provisioning', 'verify', 'SQL 生命周期用例', 'sql-case');
COMMIT;

BEGIN;
SELECT set_config('app.tenant_id', :lc_b, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:lc_b, 'lc-beta', '生命周期乙', 'active', 'standard');
INSERT INTO tenancy_profile (tenant_id, brand_name, brand_color, locale, timezone, features, updated_by)
VALUES (:lc_b, '生命周期乙', '#8250df', 'zh-CN', 'Asia/Shanghai', '{}'::jsonb, 'verify');
INSERT INTO tenancy_domain (tenant_id, domain, kind, verified)
VALUES (:lc_b, 'lc-beta.example.com', 'primary', true);
-- 与甲租户同号：主键包含 tenant_id，必须成功
INSERT INTO tenancy_business_number (tenant_id, scope, business_number, sequence)
VALUES (:lc_b, 'WO', 'WO-000001', 1);
COMMIT;

-- 2. 每个租户只能看到自己的同号记录；同租户重复编号必须被主键拒绝
BEGIN;
SELECT set_config('app.tenant_id', :lc_a, true);
DO $$
DECLARE
    visible bigint;
BEGIN
    SELECT count(*) INTO visible FROM tenancy_business_number WHERE business_number = 'WO-000001';
    IF visible <> 1 THEN
        RAISE EXCEPTION '甲租户应只看到 1 条同号记录，实际 %', visible;
    END IF;

    BEGIN
        INSERT INTO tenancy_business_number (tenant_id, scope, business_number, sequence)
        VALUES ('aaaaaaaa-0000-4000-8000-000000000001', 'WO', 'WO-000001', 2);
        RAISE EXCEPTION '同租户重复编号竟然成功';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 3. 非法状态与支持访问职责分离必须被数据库拒绝
BEGIN;
SELECT set_config('app.tenant_id', :lc_a, true);
DO $$
BEGIN
    BEGIN
        UPDATE tenancy_tenant SET status = 'closed' WHERE id = 'aaaaaaaa-0000-4000-8000-000000000001';
        RAISE EXCEPTION '旧状态 closed 未被拒绝';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO tenancy_support_access
            (tenant_id, ticket_id, operator_id, reason, requested_minutes, status,
             approved_by, approved_at, expires_at)
        VALUES ('aaaaaaaa-0000-4000-8000-000000000001', 'TCK-9001', 'ops-a',
                '验证审批人不得与申请人相同', 15, 'approved',
                'ops-a', now(), now() + interval '15 minutes');
        RAISE EXCEPTION '申请人与审批人相同竟然通过';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 4. 无租户上下文时，租户表必须返回 0 行；套餐目录是平台级数据，不受 RLS 影响
DO $$
DECLARE
    tenants bigint;
    numbers bigint;
    plans bigint;
BEGIN
    SELECT count(*) INTO tenants FROM tenancy_tenant;
    SELECT count(*) INTO numbers FROM tenancy_business_number;
    IF tenants <> 0 OR numbers <> 0 THEN
        RAISE EXCEPTION '无上下文时租户表应返回 0 行，实际 tenants=%, numbers=%', tenants, numbers;
    END IF;
    SELECT count(*) INTO plans FROM tenancy_plan;
    IF plans < 3 THEN
        RAISE EXCEPTION '套餐目录应有 trial/standard/enterprise 至少 3 条，实际 %', plans;
    END IF;
END
$$;

-- 5. 跨租户写入必须被 WITH CHECK 拒绝
BEGIN;
SELECT set_config('app.tenant_id', :lc_a, true);
DO $$
BEGIN
    BEGIN
        INSERT INTO tenancy_quota (tenant_id, quota_key, limit_value)
        VALUES ('bbbbbbbb-0000-4000-8000-000000000002', 'users', 10);
        RAISE EXCEPTION '甲租户上下文竟然能写乙租户配额';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;
END
$$;
COMMIT;

\echo 'WP02-01 租户生命周期数据库断言通过'
