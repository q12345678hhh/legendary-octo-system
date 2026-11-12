-- WP02-05：职责分离与季度访问复核的数据库断言。
-- 以 platform_app 身份执行；租户操作都在显式事务内用 set_config(..., true) 建立上下文。

\set sep_e '''eeeeeeee-0000-4000-8000-000000000005'''
\set sep_f '''ffffffff-0000-4000-8000-000000000006'''

-- 1. 无租户上下文：新租户表一行不可见；复核队列与权限目录仍可读
DO $$
DECLARE
    hidden integer;
    catalog integer;
    queued integer;
BEGIN
    SELECT count(*) INTO hidden FROM authorization_access_request;
    IF hidden <> 0 THEN
        RAISE EXCEPTION '无上下文时 authorization_access_request 应不可见，实际 % 行', hidden;
    END IF;
    SELECT count(*) INTO hidden FROM authorization_access_review;
    IF hidden <> 0 THEN
        RAISE EXCEPTION '无上下文时 authorization_access_review 应不可见，实际 % 行', hidden;
    END IF;
    SELECT count(*) INTO hidden FROM authorization_access_review_item;
    IF hidden <> 0 THEN
        RAISE EXCEPTION '无上下文时 authorization_access_review_item 应不可见，实际 % 行', hidden;
    END IF;
    SELECT count(*) INTO hidden FROM authorization_admin_action;
    IF hidden <> 0 THEN
        RAISE EXCEPTION '无上下文时 authorization_admin_action 应不可见，实际 % 行', hidden;
    END IF;

    SELECT count(*) INTO catalog FROM authorization_permission;
    IF catalog < 40 THEN
        RAISE EXCEPTION '全局权限目录应有至少 40 条，实际 %', catalog;
    END IF;

    SELECT count(*) INTO queued FROM platform_access_review_due;
    IF queued < 2 THEN
        RAISE EXCEPTION '复核队列应为现有租户回填，实际 % 行', queued;
    END IF;
END
$$;

-- 2. 租户甲：申请 → 他人批准 → 授权生效；申请人与批准人不能相同
BEGIN;
SELECT set_config('app.tenant_id', :sep_e, true);
DO $$
DECLARE
    v_request uuid := 'c1000000-0000-4000-8000-000000000001';
    v_assignment uuid := 'c2000000-0000-4000-8000-000000000001';
    v_review uuid := 'c3000000-0000-4000-8000-000000000001';
    v_item uuid := 'c4000000-0000-4000-8000-000000000001';
    v0 bigint;
    v1 bigint;
    linked uuid;
BEGIN
    INSERT INTO identity_actor (id, tenant_id, actor_type, display_name)
    VALUES ('c0000000-0000-4000-8000-000000000001', 'eeeeeeee-0000-4000-8000-000000000005',
            'service', '申请人'),
           ('c0000000-0000-4000-8000-000000000002', 'eeeeeeee-0000-4000-8000-000000000005',
            'service', '批准人'),
           ('c0000000-0000-4000-8000-000000000003', 'eeeeeeee-0000-4000-8000-000000000005',
            'service', '被授权人');

    SELECT version INTO v0 FROM authorization_cache_version
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005';

    INSERT INTO authorization_access_request
        (id, tenant_id, requester_actor_id, requested_for_actor_id, role_code,
         scope_type, valid_to, reason)
    VALUES (v_request, 'eeeeeeee-0000-4000-8000-000000000005',
            'c0000000-0000-4000-8000-000000000001',
            'c0000000-0000-4000-8000-000000000003',
            'payroll-preparer', 'tenant', now() + interval '30 days', '薪酬制单需要');

    SELECT version INTO v1 FROM authorization_cache_version
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005';
    IF v1 <= v0 THEN
        RAISE EXCEPTION '新增申请后缓存版本未递增：% -> %', v0, v1;
    END IF;

    -- 申请人自己批准必须被数据库拒绝
    BEGIN
        UPDATE authorization_access_request
           SET state = 'approved', decided_by_actor_id = 'c0000000-0000-4000-8000-000000000001',
               decided_at = now(), updated_at = now()
         WHERE id = v_request;
        RAISE EXCEPTION '申请人自己批准竟然成功';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    -- 他人批准后写入授权，并回填授权 ID
    UPDATE authorization_access_request
       SET state = 'approved', decided_by_actor_id = 'c0000000-0000-4000-8000-000000000002',
           decided_at = now(), decision_note = '同意', updated_at = now()
     WHERE id = v_request;

    INSERT INTO authorization_assignment
        (id, tenant_id, actor_id, role_code, scope_type, valid_from, valid_to,
         reason, granted_by, source_request_id, review_required)
    VALUES (v_assignment, 'eeeeeeee-0000-4000-8000-000000000005',
            'c0000000-0000-4000-8000-000000000003', 'payroll-preparer', 'tenant',
            now() - interval '1 minute', now() + interval '30 days',
            '按申请授予薪酬制单', 'c0000000-0000-4000-8000-000000000002',
            v_request, true);

    UPDATE authorization_access_request SET assignment_id = v_assignment, updated_at = now()
     WHERE id = v_request;

    SELECT assignment_id INTO linked FROM authorization_access_request WHERE id = v_request;
    IF linked IS DISTINCT FROM v_assignment THEN
        RAISE EXCEPTION '批准后未回填授权 ID，实际 %', linked;
    END IF;

    -- 同一主体同一角色同一范围的待处理申请只能有一条
    INSERT INTO authorization_access_request
        (tenant_id, requester_actor_id, requested_for_actor_id, role_code, scope_type, reason)
    VALUES ('eeeeeeee-0000-4000-8000-000000000005',
            'c0000000-0000-4000-8000-000000000001',
            'c0000000-0000-4000-8000-000000000003', 'payroll-reviewer', 'tenant', '复核需要');
    BEGIN
        INSERT INTO authorization_access_request
            (tenant_id, requester_actor_id, requested_for_actor_id, role_code, scope_type, reason)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005',
                'c0000000-0000-4000-8000-000000000001',
                'c0000000-0000-4000-8000-000000000003', 'payroll-reviewer', 'tenant', '重复申请');
        RAISE EXCEPTION '重复待处理申请竟然成功';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    -- 季度复核：每季度一份、条目唯一、复核人不能是主体本人
    INSERT INTO authorization_access_review
        (id, tenant_id, period_start, period_end, due_at)
    VALUES (v_review, 'eeeeeeee-0000-4000-8000-000000000005',
            DATE '2026-01-01', DATE '2026-04-01', now() + interval '14 days');

    BEGIN
        INSERT INTO authorization_access_review
            (tenant_id, period_start, period_end, due_at)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005',
                DATE '2026-01-01', DATE '2026-04-01', now() + interval '14 days');
        RAISE EXCEPTION '同季度重复复核竟然成功';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    INSERT INTO authorization_access_review_item
        (id, tenant_id, review_id, assignment_id, subject_actor_id, reviewer_actor_id)
    VALUES (v_item, 'eeeeeeee-0000-4000-8000-000000000005', v_review, v_assignment,
            'c0000000-0000-4000-8000-000000000003',
            'c0000000-0000-4000-8000-000000000002');

    BEGIN
        INSERT INTO authorization_access_review_item
            (tenant_id, review_id, assignment_id, subject_actor_id, reviewer_actor_id)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005', v_review, v_assignment,
                'c0000000-0000-4000-8000-000000000003',
                'c0000000-0000-4000-8000-000000000001');
        RAISE EXCEPTION '同一授权重复复核条目竟然成功';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO authorization_access_review_item
            (tenant_id, review_id, assignment_id, subject_actor_id, reviewer_actor_id)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005', v_review, v_assignment,
                'c0000000-0000-4000-8000-000000000001',
                'c0000000-0000-4000-8000-000000000001');
        RAISE EXCEPTION '复核人等于主体竟然成功';
    EXCEPTION
        WHEN check_violation OR unique_violation THEN
            NULL;
    END;

    BEGIN
        UPDATE authorization_access_review_item SET state = 'confirmed'
         WHERE id = v_item;
        RAISE EXCEPTION '已决条目缺少 decided_at 竟然成功';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    -- 管理员审计只允许插入
    INSERT INTO authorization_admin_action
        (tenant_id, actor_id, action, target_type, target_id, detail)
    VALUES ('eeeeeeee-0000-4000-8000-000000000005',
            'c0000000-0000-4000-8000-000000000002', 'ACCESS_REQUEST_APPROVED',
            'authorization.access_request', v_request, '{"emergency":false}'::jsonb);

    BEGIN
        UPDATE authorization_admin_action SET action = 'TAMPERED'
         WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005';
        RAISE EXCEPTION '管理员审计竟然可以被修改';
    EXCEPTION WHEN raise_exception THEN
        NULL;
    END;

    BEGIN
        DELETE FROM authorization_admin_action
         WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000005';
        RAISE EXCEPTION '管理员审计竟然可以被删除';
    EXCEPTION WHEN raise_exception THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 3. 跨租户引用必须被拒绝
BEGIN;
SELECT set_config('app.tenant_id', :sep_e, true);
DO $$
BEGIN
    BEGIN
        INSERT INTO authorization_access_request
            (tenant_id, requester_actor_id, requested_for_actor_id, role_code, scope_type, reason)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005',
                'b0000000-0000-4000-8000-000000000001',
                'c0000000-0000-4000-8000-000000000003',
                'payroll-preparer', 'tenant', '跨租户申请人');
        RAISE EXCEPTION '跨租户申请人竟然写入成功';
    EXCEPTION WHEN foreign_key_violation THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 4. 跨租户隔离：租户乙看不到租户甲的申请、复核与审计
BEGIN;
SELECT set_config('app.tenant_id', :sep_f, true);
DO $$
DECLARE
    visible integer;
BEGIN
    SELECT count(*) INTO visible FROM authorization_access_request;
    IF visible <> 0 THEN
        RAISE EXCEPTION '租户乙应看不到租户甲申请，实际 % 行', visible;
    END IF;
    SELECT count(*) INTO visible FROM authorization_access_review;
    IF visible <> 0 THEN
        RAISE EXCEPTION '租户乙应看不到租户甲复核，实际 % 行', visible;
    END IF;
    SELECT count(*) INTO visible FROM authorization_admin_action;
    IF visible <> 0 THEN
        RAISE EXCEPTION '租户乙应看不到租户甲审计，实际 % 行', visible;
    END IF;
END
$$;
COMMIT;

-- 5. 新租户自动播种下一次季度复核
BEGIN;
SELECT set_config('app.tenant_id', 'cccccccc-0000-4000-8000-000000000009', true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES ('cccccccc-0000-4000-8000-000000000009', 'sep-gamma', '职责分离丙', 'active', 'standard');
COMMIT;

DO $$
DECLARE
    seeded integer;
BEGIN
    SELECT count(*) INTO seeded FROM platform_access_review_due
     WHERE tenant_id = 'cccccccc-0000-4000-8000-000000000009'
       AND next_run_at > now();
    IF seeded <> 1 THEN
        RAISE EXCEPTION '新租户未播种季度复核队列，实际 % 行', seeded;
    END IF;
END
$$;

-- 6. 结构：不可变触发器与授权增列
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'authorization_assignment' AND column_name = 'source_request_id'
    ) THEN
        RAISE EXCEPTION 'authorization_assignment.source_request_id 不存在';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'authorization_assignment' AND column_name = 'review_required'
    ) THEN
        RAISE EXCEPTION 'authorization_assignment.review_required 不存在';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'authorization_admin_action_immutable_trigger'
    ) THEN
        RAISE EXCEPTION '管理员审计不可变触发器不存在';
    END IF;
END
$$;

\echo 'WP02-05 职责分离数据库断言通过'
