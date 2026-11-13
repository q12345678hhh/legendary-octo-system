-- WP02-08：删除、保留与数据主体请求的数据库断言。以运行期账号 platform_app 执行，失败即 RAISE EXCEPTION。
--
-- 重点验证四件事：
--   1. 租户侧三张表启用并强制 RLS，平台侧三张元数据表按设计不启用 RLS；
--   2. 回收站到期时间、状态约束、活跃唯一索引与受保护数据类约束由数据库兜底；
--   3. 留痕表与墓碑表只允许插入，改删一律抛错；
--   4. 保留策略预置值与合规下限、作业类型白名单真实生效。

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

-- ---------------------------------------------------------------- 1 表存在与 RLS 开关

DO $$
DECLARE
    tbl text;
    tenant_tables text[] := ARRAY['retention_bin_item', 'retention_legal_hold', 'retention_event'];
    platform_tables text[] := ARRAY[
        'platform_deletion_request', 'platform_deletion_tombstone', 'platform_tombstone_replay'];
    flags record;
BEGIN
    FOREACH tbl IN ARRAY tenant_tables LOOP
        SELECT c.relrowsecurity, c.relforcerowsecurity INTO flags
          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = tbl;
        IF NOT FOUND THEN
            RAISE EXCEPTION '缺少租户侧保留表：%', tbl;
        END IF;
        IF NOT flags.relrowsecurity OR NOT flags.relforcerowsecurity THEN
            RAISE EXCEPTION '% 必须启用并强制 RLS', tbl;
        END IF;
    END LOOP;

    FOREACH tbl IN ARRAY platform_tables LOOP
        SELECT c.relrowsecurity INTO flags
          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = tbl;
        IF NOT FOUND THEN
            RAISE EXCEPTION '缺少平台侧保留表：%', tbl;
        END IF;
        IF flags.relrowsecurity THEN
            RAISE EXCEPTION '% 是平台元数据表，不应启用 RLS', tbl;
        END IF;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------- 2 保留策略预置与合规下限

DO $$
DECLARE
    audit_days int;
    log_days int;
    bin_days int;
BEGIN
    SELECT value::text::int INTO audit_days FROM platform_setting WHERE setting_key = 'retention.audit_days';
    SELECT value::text::int INTO log_days FROM platform_setting WHERE setting_key = 'retention.business_log_days';
    SELECT value::text::int INTO bin_days FROM platform_setting WHERE setting_key = 'retention.recycle_bin_days';
    IF audit_days IS DISTINCT FROM 2555 THEN
        RAISE EXCEPTION '审计保留默认值必须是 2555 天，实际 %', audit_days;
    END IF;
    IF log_days IS DISTINCT FROM 180 THEN
        RAISE EXCEPTION '业务日志保留默认值必须是 180 天，实际 %', log_days;
    END IF;
    IF bin_days IS DISTINCT FROM 30 THEN
        RAISE EXCEPTION '回收站默认保留必须是 30 天，实际 %', bin_days;
    END IF;
END
$$;

-- ---------------------------------------------------------------- 3 复用种子租户验证 RLS 隔离

BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001';

INSERT INTO retention_bin_item
    (tenant_id, data_class, object_id, object_ref, snapshot, deleted_by, purge_after)
VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'file',
        'e5e5e5e5-0000-4000-8000-000000000001', 'wp0208-a/file/one.txt',
        '{"fileName":"one.txt","contentType":"text/plain"}'::jsonb, 'tester',
        now() + interval '30 days');

DO $$
DECLARE
    visible int;
    gap interval;
BEGIN
    SELECT count(*) INTO visible FROM retention_bin_item;
    IF visible <> 1 THEN
        RAISE EXCEPTION '本租户应看到 1 条回收站记录，实际 %', visible;
    END IF;
    SELECT purge_after - deleted_at INTO gap FROM retention_bin_item LIMIT 1;
    IF gap < interval '29 days' OR gap > interval '31 days' THEN
        RAISE EXCEPTION '回收站默认保留期不是 30 天：%', gap;
    END IF;
END
$$;

SET LOCAL app.tenant_id = 'bbbbbbbb-0000-0000-0000-000000000002';

DO $$
DECLARE
    visible int;
BEGIN
    SELECT count(*) INTO visible FROM retention_bin_item;
    IF visible <> 0 THEN
        RAISE EXCEPTION '跨租户可见：租户 B 看到了 % 条租户 A 的回收站记录', visible;
    END IF;
END
$$;
COMMIT;

-- ---------------------------------------------------------------- 4 约束兜底

BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001';

DO $$
DECLARE
    blocked boolean;
    state text;
BEGIN
    -- 同一对象只能有一条 active 记录
    blocked := false;
    BEGIN
        INSERT INTO retention_bin_item
            (tenant_id, data_class, object_id, snapshot, deleted_by, purge_after)
        VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'file',
                'e5e5e5e5-0000-4000-8000-000000000001', '{}'::jsonb, 'tester',
                now() + interval '30 days');
    EXCEPTION WHEN unique_violation THEN
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '同一对象出现了两条 active 回收站记录';
    END IF;

    -- 到期时间必须晚于删除时间
    blocked := false;
    BEGIN
        INSERT INTO retention_bin_item
            (tenant_id, data_class, object_id, snapshot, deleted_by, purge_after)
        VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'file',
                'e5e5e5e5-0000-4000-8000-000000000002', '{}'::jsonb, 'tester', now());
    EXCEPTION WHEN check_violation THEN
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION 'purge_after 早于 deleted_at 竟然被接受';
    END IF;

    -- 受保护数据类不能进回收站
    blocked := false;
    BEGIN
        INSERT INTO retention_bin_item
            (tenant_id, data_class, object_id, snapshot, deleted_by, purge_after)
        VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'payroll_result',
                'e5e5e5e5-0000-4000-8000-000000000003', '{}'::jsonb, 'tester',
                now() + interval '30 days');
    EXCEPTION WHEN check_violation THEN
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '工资结果竟然可以进入回收站';
    END IF;

    -- 留痕表只允许插入
    INSERT INTO retention_event (tenant_id, action, data_class, object_id, actor, detail)
    VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'recycled', 'file',
            'e5e5e5e5-0000-4000-8000-000000000001', 'tester', '{}'::jsonb);

    blocked := false;
    BEGIN
        UPDATE retention_event SET actor = 'tampered';
    EXCEPTION WHEN OTHERS THEN
        state := SQLSTATE;
        blocked := state = 'P0001';
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION 'retention_event 竟然允许 UPDATE，SQLSTATE=%', coalesce(state, 'none');
    END IF;

    blocked := false;
    BEGIN
        DELETE FROM retention_event;
    EXCEPTION WHEN OTHERS THEN
        state := SQLSTATE;
        blocked := state = 'P0001';
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION 'retention_event 竟然允许 DELETE，SQLSTATE=%', coalesce(state, 'none');
    END IF;
END
$$;
COMMIT;

-- ---------------------------------------------------------------- 5 墓碑不可改删

DO $$
DECLARE
    tomb uuid;
    blocked boolean;
    state text;
BEGIN
    INSERT INTO platform_deletion_tombstone
        (tenant_id, data_class, object_id, subject_hash, action)
    VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'person',
            'f6f6f6f6-0000-4000-8000-000000000001', repeat('a', 64), 'anonymized')
    RETURNING id INTO tomb;

    blocked := false;
    BEGIN
        UPDATE platform_deletion_tombstone SET action = 'purged' WHERE id = tomb;
    EXCEPTION WHEN OTHERS THEN
        state := SQLSTATE;
        blocked := state = 'P0001';
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '墓碑竟然允许 UPDATE，SQLSTATE=%', coalesce(state, 'none');
    END IF;

    blocked := false;
    BEGIN
        DELETE FROM platform_deletion_tombstone WHERE id = tomb;
    EXCEPTION WHEN OTHERS THEN
        state := SQLSTATE;
        blocked := state = 'P0001';
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '墓碑竟然允许 DELETE，SQLSTATE=%', coalesce(state, 'none');
    END IF;
END
$$;

-- ---------------------------------------------------------------- 6 作业类型与设置键白名单

DO $$
DECLARE
    blocked boolean;
BEGIN
    INSERT INTO platform_operation_run (id, kind, status, summary)
    VALUES (gen_random_uuid(), 'retention_purge', 'succeeded', 'wp0208 probe');

    INSERT INTO platform_operation_run (id, kind, status, summary)
    VALUES (gen_random_uuid(), 'tombstone_replay', 'succeeded', 'wp0208 probe');

    blocked := false;
    BEGIN
        INSERT INTO platform_operation_run (id, kind, status, summary)
        VALUES (gen_random_uuid(), 'bogus_kind', 'succeeded', 'x');
    EXCEPTION WHEN check_violation THEN
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '未知作业类型竟然被接受';
    END IF;

    INSERT INTO platform_setting (setting_key, category, value, is_secret, updated_by)
    VALUES ('retention.probe_days', 'retention', '30'::jsonb, false, 'verify');

    blocked := false;
    BEGIN
        INSERT INTO platform_setting (setting_key, category, value, is_secret, updated_by)
        VALUES ('retention.probe_days2', 'bogus', '30'::jsonb, false, 'verify');
    EXCEPTION WHEN check_violation THEN
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '未知设置分类竟然被接受';
    END IF;

    DELETE FROM platform_setting WHERE setting_key = 'retention.probe_days';
END
$$;

\echo 'WP02-08 删除、保留与数据主体请求数据库断言通过'
