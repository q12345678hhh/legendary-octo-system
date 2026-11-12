-- WP02-07：平台运营端数据库断言。以运行期账号 platform_app 执行，任何一条失败都会 RAISE EXCEPTION。
--
-- 重点验证三件事：
--   1. 平台元数据表确实不启用 RLS（设计例外），且纯平台表没有 tenant_id；
--   2. 目录投影、审计不可变、设置白名单、指标幂等、安全事件状态机由数据库兜底；
--   3. 运行账号仍然 NOBYPASSRLS，平台能力没有靠放宽数据库权限实现。

-- ---------------------------------------------------------------- 0 执行身份

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

-- ---------------------------------------------------------------- 1 平台表存在且不启用 RLS

DO $$
DECLARE
    tbl text;
    platform_tables text[] := ARRAY[
        'platform_operator', 'platform_operator_session', 'platform_audit_event',
        'platform_security_event', 'platform_tenant_directory', 'platform_tenant_metric_daily',
        'platform_ai_cost_daily', 'platform_app_version', 'platform_connector',
        'platform_plugin_signature', 'platform_model_provider', 'platform_setting',
        'platform_operation_run'];
    pure_tables text[] := ARRAY[
        'platform_operator', 'platform_operator_session', 'platform_audit_event',
        'platform_ai_cost_daily', 'platform_app_version', 'platform_connector',
        'platform_plugin_signature', 'platform_model_provider', 'platform_setting'];
BEGIN
    FOREACH tbl IN ARRAY platform_tables LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.tables t
                        WHERE t.table_schema = 'public' AND t.table_name = tbl)
        THEN
            RAISE EXCEPTION '缺少平台表：%', tbl;
        END IF;
        IF (SELECT relrowsecurity FROM pg_class
             WHERE oid = format('public.%I', tbl)::regclass)
        THEN
            RAISE EXCEPTION '平台元数据表 % 被误启用 RLS：平台控制面必须跨租户读', tbl;
        END IF;
    END LOOP;

    FOREACH tbl IN ARRAY pure_tables LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns c
                    WHERE c.table_schema = 'public' AND c.table_name = tbl
                      AND c.column_name = 'tenant_id')
        THEN
            RAISE EXCEPTION '纯平台表 % 不应包含 tenant_id', tbl;
        END IF;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------- 2 租户目录投影同步

BEGIN;
SELECT set_config('app.tenant_id', 'c3c3c3c3-0000-4000-8000-000000000003', true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES ('c3c3c3c3-0000-4000-8000-000000000003', 'ops-dir-alpha', '平台目录同步租户',
        'active', 'standard')
ON CONFLICT (id) DO UPDATE SET status = 'active', plan_code = 'standard';

DO $$
DECLARE
    row_status text;
    row_plan text;
BEGIN
    SELECT status, plan_code INTO row_status, row_plan
      FROM platform_tenant_directory
     WHERE id = 'c3c3c3c3-0000-4000-8000-000000000003';
    IF row_status IS DISTINCT FROM 'active' OR row_plan IS DISTINCT FROM 'standard' THEN
        RAISE EXCEPTION '目录投影未同步：status=%, plan=%', row_status, row_plan;
    END IF;
END
$$;

UPDATE tenancy_tenant SET plan_code = 'enterprise'
 WHERE id = 'c3c3c3c3-0000-4000-8000-000000000003';

DO $$
DECLARE
    row_status text;
    row_plan text;
BEGIN
    SELECT status, plan_code INTO row_status, row_plan
      FROM platform_tenant_directory
     WHERE id = 'c3c3c3c3-0000-4000-8000-000000000003';
    IF row_status IS DISTINCT FROM 'active' OR row_plan IS DISTINCT FROM 'enterprise' THEN
        RAISE EXCEPTION '目录投影未跟随套餐更新：status=%, plan=%', row_status, row_plan;
    END IF;
END
$$;

UPDATE tenancy_tenant
   SET status = 'frozen', frozen_at = now(), frozen_reason = '平台目录同步验证'
 WHERE id = 'c3c3c3c3-0000-4000-8000-000000000003';

DO $$
DECLARE
    row_status text;
    row_plan text;
BEGIN
    SELECT status, plan_code INTO row_status, row_plan
      FROM platform_tenant_directory
     WHERE id = 'c3c3c3c3-0000-4000-8000-000000000003';
    IF row_status IS DISTINCT FROM 'frozen' OR row_plan IS DISTINCT FROM 'enterprise' THEN
        RAISE EXCEPTION '目录投影未跟随状态更新：status=%, plan=%', row_status, row_plan;
    END IF;
END
$$;
COMMIT;

-- ---------------------------------------------------------------- 3 平台审计不可改删

DO $$
DECLARE
    audit_id uuid;
    blocked boolean := false;
    state text;
BEGIN
    INSERT INTO platform_audit_event (action, target_type, target_id, detail)
    VALUES ('verify.probe', 'platform_audit_event', 'probe', '{}'::jsonb)
    RETURNING id INTO audit_id;

    BEGIN
        UPDATE platform_audit_event SET action = 'tampered' WHERE id = audit_id;
    EXCEPTION WHEN OTHERS THEN
        state := SQLSTATE;
        blocked := state = 'P0001';
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '平台审计竟然允许 UPDATE，SQLSTATE=%', coalesce(state, 'none');
    END IF;

    blocked := false;
    BEGIN
        DELETE FROM platform_audit_event WHERE id = audit_id;
    EXCEPTION WHEN OTHERS THEN
        state := SQLSTATE;
        blocked := state = 'P0001';
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '平台审计竟然允许 DELETE，SQLSTATE=%', coalesce(state, 'none');
    END IF;
END
$$;

-- ---------------------------------------------------------------- 4 设置键白名单

DO $$
DECLARE
    blocked boolean := false;
    invalid_count integer;
BEGIN
    BEGIN
        INSERT INTO platform_setting (setting_key, category, value)
        VALUES ('ops.arbitrary_key', 'limits', '1'::jsonb);
    EXCEPTION WHEN check_violation THEN
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '未登记的设置键竟然可以写入';
    END IF;

    SELECT count(*) INTO invalid_count FROM platform_setting
     WHERE setting_key IN ('email.password', 'storage.secret_key', 'model.gateway_key');
    IF invalid_count > 0 THEN
        RAISE EXCEPTION '密钥类设置被预置了 % 条，默认必须为空', invalid_count;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM platform_setting
                    WHERE setting_key = 'limits.max_tenants' AND value = '1000'::jsonb) THEN
        RAISE EXCEPTION '缺少 limits.max_tenants 默认值';
    END IF;
END
$$;

-- ---------------------------------------------------------------- 5 指标 upsert 幂等

BEGIN;
INSERT INTO platform_tenant_metric_daily (tenant_id, day, active_users, storage_bytes)
VALUES ('c3c3c3c3-0000-4000-8000-000000000003', DATE '2026-11-12', 3, 1024)
ON CONFLICT (tenant_id, day) DO UPDATE
   SET active_users = EXCLUDED.active_users, storage_bytes = EXCLUDED.storage_bytes;

INSERT INTO platform_tenant_metric_daily (tenant_id, day, active_users, storage_bytes)
VALUES ('c3c3c3c3-0000-4000-8000-000000000003', DATE '2026-11-12', 5, 2048)
ON CONFLICT (tenant_id, day) DO UPDATE
   SET active_users = EXCLUDED.active_users, storage_bytes = EXCLUDED.storage_bytes;

DO $$
DECLARE
    rows integer;
    users integer;
    bytes bigint;
BEGIN
    SELECT count(*), max(active_users), max(storage_bytes)
      INTO rows, users, bytes
      FROM platform_tenant_metric_daily
     WHERE tenant_id = 'c3c3c3c3-0000-4000-8000-000000000003'
       AND day = DATE '2026-11-12';
    IF rows <> 1 OR users <> 5 OR bytes <> 2048 THEN
        RAISE EXCEPTION '指标 upsert 非幂等：rows=%, users=%, bytes=%', rows, users, bytes;
    END IF;
END
$$;
COMMIT;

-- ---------------------------------------------------------------- 6 安全事件类别与状态机

DO $$
DECLARE
    event_id uuid;
    blocked boolean := false;
    state text;
    row_status text;
BEGIN
    BEGIN
        INSERT INTO platform_security_event (category, severity, summary)
        VALUES ('made_up_category', 'high', '伪造类别');
    EXCEPTION WHEN check_violation THEN
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '未知安全事件类别竟然可以写入';
    END IF;

    INSERT INTO platform_security_event
        (tenant_id, category, severity, summary, detail)
    VALUES ('c3c3c3c3-0000-4000-8000-000000000003', 'abnormal_login', 'high',
            '验证用异常登录事件', '{"failures":11}'::jsonb)
    RETURNING id INTO event_id;

    UPDATE platform_security_event
       SET status = 'acknowledged', acknowledged_by = 'verify@example.com',
           acknowledged_at = now()
     WHERE id = event_id;
    UPDATE platform_security_event
       SET status = 'resolved', resolved_by = 'verify@example.com', resolved_at = now()
     WHERE id = event_id;

    SELECT status INTO row_status FROM platform_security_event WHERE id = event_id;
    IF row_status <> 'resolved' THEN
        RAISE EXCEPTION '安全事件状态机异常：%', row_status;
    END IF;

    BEGIN
        UPDATE platform_security_event SET status = 'resolved', resolved_by = NULL
         WHERE id = event_id;
    EXCEPTION WHEN check_violation THEN
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '已解决事件缺少解决人竟然被接受';
    END IF;
END
$$;

-- ---------------------------------------------------------------- 7 运营账号与会话约束

DO $$
DECLARE
    operator_id uuid;
    blocked boolean := false;
    state text;
BEGIN
    BEGIN
        INSERT INTO platform_operator (email, display_name, role_code, password_hash)
        VALUES ('bad@example.com', '非法角色', 'super-admin', 'x');
    EXCEPTION WHEN check_violation THEN
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '未知运营角色竟然可以写入';
    END IF;

    blocked := false;
    BEGIN
        INSERT INTO platform_operator (email, display_name, role_code, password_hash)
        VALUES ('Bad@Example.com', '大写邮箱', 'platform-auditor', 'x');
    EXCEPTION WHEN check_violation THEN
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '未归一化的大写邮箱竟然可以写入';
    END IF;

    INSERT INTO platform_operator (email, display_name, role_code, password_hash)
    VALUES ('verify-ops@example.com', '验证运营', 'platform-owner', 'hash')
    ON CONFLICT (email) DO UPDATE SET display_name = EXCLUDED.display_name
    RETURNING id INTO operator_id;

    blocked := false;
    BEGIN
        INSERT INTO platform_operator_session
            (operator_id, token_hash, idle_expires_at, expires_at, created_at)
        VALUES (operator_id, 'expired-token', now(), now() - interval '1 hour',
                now());
    EXCEPTION WHEN check_violation THEN
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '过期会话竟然可以写入';
    END IF;
END
$$;

\echo 'WP02-07 平台运营数据库断言通过'
