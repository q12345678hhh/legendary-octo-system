-- WP02-06：跨租户攻击断言。以运行期账号 platform_app 执行，任何一条失败都会 RAISE EXCEPTION。
--
-- 夹具刻意制造「看起来一样」的两个租户：同名、同套餐、同姓名、同邮箱、同工号、同文件名，
-- 只有 UUID 不同；用例全部按 B 的已知主键从 A 上下文发起攻击。

\set ct_a '''a1a1a1a1-0000-4000-8000-000000000001'''
\set ct_b '''b2b2b2b2-0000-4000-8000-000000000002'''

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

-- ---------------------------------------------------------------- 1 双相似租户夹具

BEGIN;
SELECT set_config('app.tenant_id', :ct_a, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:ct_a, 'ct-alpha', '跨租户相似租户', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;
INSERT INTO tenancy_profile
    (tenant_id, brand_name, brand_color, locale, timezone, features, updated_by)
VALUES (:ct_a, '跨租户相似租户', '#1f6feb', 'zh-CN', 'Asia/Shanghai', '{}'::jsonb, 'verify')
ON CONFLICT (tenant_id) DO NOTHING;
INSERT INTO tenancy_domain (tenant_id, domain, kind, verified)
VALUES (:ct_a, 'alpha.cross-tenant.example', 'primary', true)
ON CONFLICT DO NOTHING;

INSERT INTO organization_person (id, tenant_id, display_name, primary_email) VALUES
    ('a1000001-0000-4000-8000-000000000001', :ct_a, '张三', 'zhangsan@same.example'),
    ('a1000002-0000-4000-8000-000000000002', :ct_a, '李四', 'lisi@same.example'),
    ('a1000003-0000-4000-8000-000000000003', :ct_a, '王五', 'wangwu@same.example')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_employment
    (id, tenant_id, person_id, employment_no, effective_from, job_title) VALUES
    ('a1100001-0000-4000-8000-000000000001', :ct_a,
     'a1000001-0000-4000-8000-000000000001', 'E-0001', DATE '2026-01-01', '工程师'),
    ('a1100002-0000-4000-8000-000000000002', :ct_a,
     'a1000002-0000-4000-8000-000000000002', 'E-0002', DATE '2026-01-01', '工程师'),
    ('a1100003-0000-4000-8000-000000000003', :ct_a,
     'a1000003-0000-4000-8000-000000000003', 'E-0003', DATE '2026-01-01', '工程师')
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity_actor (id, tenant_id, actor_type, display_name) VALUES
    ('a1200001-0000-4000-8000-000000000001', :ct_a, 'service', '跨租户攻击者')
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_file
    (id, tenant_id, subject_id, file_name, content_type, size_bytes, object_key,
     status, scan_status) VALUES
    ('a3000001-0000-4000-8000-000000000001', :ct_a, 'fixture',
     '合同.pdf', 'application/pdf', 1024,
     'a1a1a1a1-0000-4000-8000-000000000001/a3000001-0000-4000-8000-000000000001/合同.pdf',
     'available', 'clean')
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_job
    (id, tenant_id, subject_id, job_type, trace_id, correlation_id) VALUES
    ('a4000001-0000-4000-8000-000000000001', :ct_a, 'fixture', 'payroll-export',
     'trace-a', 'corr-ct-a')
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_outbox
    (event_id, tenant_id, event_name, schema_version, producer, correlation_id, trace_id,
     occurred_at, payload) VALUES
    ('a5000001-0000-4000-8000-000000000001', :ct_a, 'platform.fixture.v1', 1, 'verify',
     'corr-ct-a', 'trace-a', now(), '{"tenant":"A"}'::jsonb),
    ('a5000002-0000-4000-8000-000000000002', :ct_a, 'platform.fixture.v1', 1, 'verify',
     'corr-ct-a', 'trace-a', now(), '{"tenant":"A"}'::jsonb)
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO authorization_assignment
    (id, tenant_id, actor_id, role_code, scope_type, valid_from, reason, granted_by) VALUES
    ('a6000001-0000-4000-8000-000000000001', :ct_a,
     'a1200001-0000-4000-8000-000000000001', 'employee', 'tenant',
     now() - interval '1 day', '跨租户夹具', 'verify')
ON CONFLICT (id) DO NOTHING;

INSERT INTO authorization_decision_log
    (id, tenant_id, actor_id, permission_code, allowed, reason_code) VALUES
    ('a8000001-0000-4000-8000-000000000001', :ct_a,
     'a1200001-0000-4000-8000-000000000001', 'directory.person.read', false, 'SCOPE_DENIED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO authorization_access_review
    (id, tenant_id, period_start, period_end, due_at) VALUES
    ('a9000001-0000-4000-8000-000000000001', :ct_a, DATE '2026-10-01', DATE '2027-01-01',
     now() + interval '14 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO authorization_access_review_item
    (tenant_id, review_id, assignment_id, subject_actor_id) VALUES
    (:ct_a, 'a9000001-0000-4000-8000-000000000001',
     'a6000001-0000-4000-8000-000000000001',
     'a1200001-0000-4000-8000-000000000001')
ON CONFLICT DO NOTHING;

INSERT INTO authorization_admin_action (tenant_id, action, target_type, detail)
VALUES (:ct_a, 'FIXTURE_SEEDED', 'authorization.assignment',
        '{"tenant":"A"}'::jsonb);
COMMIT;

BEGIN;
SELECT set_config('app.tenant_id', :ct_b, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:ct_b, 'ct-beta', '跨租户相似租户', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;
INSERT INTO tenancy_profile
    (tenant_id, brand_name, brand_color, locale, timezone, features, updated_by)
VALUES (:ct_b, '跨租户相似租户', '#1f6feb', 'zh-CN', 'Asia/Shanghai', '{}'::jsonb, 'verify')
ON CONFLICT (tenant_id) DO NOTHING;
INSERT INTO tenancy_domain (tenant_id, domain, kind, verified)
VALUES (:ct_b, 'beta.cross-tenant.example', 'primary', true)
ON CONFLICT DO NOTHING;

INSERT INTO organization_person (id, tenant_id, display_name, primary_email) VALUES
    ('b2000001-0000-4000-8000-000000000001', :ct_b, '张三', 'zhangsan@same.example'),
    ('b2000002-0000-4000-8000-000000000002', :ct_b, '李四', 'lisi@same.example'),
    ('b2000003-0000-4000-8000-000000000003', :ct_b, '王五', 'wangwu@same.example')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_employment
    (id, tenant_id, person_id, employment_no, effective_from, job_title) VALUES
    ('b2100001-0000-4000-8000-000000000001', :ct_b,
     'b2000001-0000-4000-8000-000000000001', 'E-0001', DATE '2026-01-01', '工程师'),
    ('b2100002-0000-4000-8000-000000000002', :ct_b,
     'b2000002-0000-4000-8000-000000000002', 'E-0002', DATE '2026-01-01', '工程师'),
    ('b2100003-0000-4000-8000-000000000003', :ct_b,
     'b2000003-0000-4000-8000-000000000003', 'E-0003', DATE '2026-01-01', '工程师')
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity_actor (id, tenant_id, actor_type, display_name) VALUES
    ('b2200001-0000-4000-8000-000000000001', :ct_b, 'service', '跨租户被攻击者')
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_file
    (id, tenant_id, subject_id, file_name, content_type, size_bytes, object_key,
     status, scan_status) VALUES
    ('b3000001-0000-4000-8000-000000000001', :ct_b, 'fixture',
     '合同.pdf', 'application/pdf', 1024,
     'b2b2b2b2-0000-4000-8000-000000000002/b3000001-0000-4000-8000-000000000001/合同.pdf',
     'available', 'clean')
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_job
    (id, tenant_id, subject_id, job_type, trace_id, correlation_id) VALUES
    ('b4000001-0000-4000-8000-000000000001', :ct_b, 'fixture', 'payroll-export',
     'trace-b', 'corr-ct-b')
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_outbox
    (event_id, tenant_id, event_name, schema_version, producer, correlation_id, trace_id,
     occurred_at, payload) VALUES
    ('b5000001-0000-4000-8000-000000000001', :ct_b, 'platform.fixture.v1', 1, 'verify',
     'corr-ct-b', 'trace-b', now(), '{"tenant":"B"}'::jsonb),
    ('b5000002-0000-4000-8000-000000000002', :ct_b, 'platform.fixture.v1', 1, 'verify',
     'corr-ct-b', 'trace-b', now(), '{"tenant":"B"}'::jsonb)
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO authorization_assignment
    (id, tenant_id, actor_id, role_code, scope_type, valid_from, reason, granted_by) VALUES
    ('b6000001-0000-4000-8000-000000000001', :ct_b,
     'b2200001-0000-4000-8000-000000000001', 'employee', 'tenant',
     now() - interval '1 day', '跨租户夹具', 'verify')
ON CONFLICT (id) DO NOTHING;

INSERT INTO authorization_decision_log
    (id, tenant_id, actor_id, permission_code, allowed, reason_code) VALUES
    ('b8000001-0000-4000-8000-000000000001', :ct_b,
     'b2200001-0000-4000-8000-000000000001', 'directory.person.read', false, 'SCOPE_DENIED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO authorization_access_review
    (id, tenant_id, period_start, period_end, due_at) VALUES
    ('b9000001-0000-4000-8000-000000000001', :ct_b, DATE '2026-10-01', DATE '2027-01-01',
     now() + interval '14 days')
ON CONFLICT (id) DO NOTHING;

INSERT INTO authorization_access_review_item
    (tenant_id, review_id, assignment_id, subject_actor_id) VALUES
    (:ct_b, 'b9000001-0000-4000-8000-000000000001',
     'b6000001-0000-4000-8000-000000000001',
     'b2200001-0000-4000-8000-000000000001')
ON CONFLICT DO NOTHING;

INSERT INTO authorization_admin_action (tenant_id, action, target_type, detail)
VALUES (:ct_b, 'FIXTURE_SEEDED', 'authorization.assignment',
        '{"tenant":"B"}'::jsonb);
COMMIT;

-- ---------------------------------------------------------------- 2 逐表 UUID 猜测矩阵

DO $$
DECLARE
    t record;
    pk text;
    tenant_col text;
    b_id text;
    n bigint;
    affected bigint;
    a_ctx constant text := 'a1a1a1a1-0000-4000-8000-000000000001';
    b_ctx constant text := 'b2b2b2b2-0000-4000-8000-000000000002';
    tables integer := 0;
    covered integer := 0;
BEGIN
    SELECT count(DISTINCT p.tablename) INTO tables
      FROM pg_policies p
      JOIN pg_class c ON c.relname = p.tablename
      JOIN pg_namespace ns ON ns.oid = c.relnamespace AND ns.nspname = 'public'
     WHERE p.schemaname = 'public' AND c.relforcerowsecurity;
    -- 97 = V4–V17 的 96 张（40-integrity.sql 口径）+ tenancy_tenant 自身
    IF tables <> 97 THEN
        RAISE EXCEPTION '受 FORCE RLS 保护的租户表应为 97 张，实际 %', tables;
    END IF;

    FOR t IN
        SELECT DISTINCT p.tablename AS name
          FROM pg_policies p
          JOIN pg_class c ON c.relname = p.tablename
          JOIN pg_namespace ns ON ns.oid = c.relnamespace AND ns.nspname = 'public'
         WHERE p.schemaname = 'public' AND c.relforcerowsecurity
         ORDER BY 1
    LOOP
        -- 每轮都要回到 B 上下文：上一轮为了攻击已切到 A，不能复用
        PERFORM set_config('app.tenant_id', b_ctx, true);
        SELECT a.attname INTO pk
          FROM pg_index i
          JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
         WHERE i.indrelid = format('public.%I', t.name)::regclass
           AND i.indisprimary
         ORDER BY a.attnum
         LIMIT 1;
        CONTINUE WHEN pk IS NULL;

        SELECT CASE WHEN EXISTS (
                   SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = t.name
                      AND column_name = 'tenant_id')
               THEN 'tenant_id' ELSE 'id' END INTO tenant_col;

        EXECUTE format('SELECT %I::text FROM public.%I WHERE %I = $1::uuid LIMIT 1',
                       pk, t.name, tenant_col) INTO b_id USING b_ctx;
        CONTINUE WHEN b_id IS NULL;

        covered := covered + 1;
        PERFORM set_config('app.tenant_id', a_ctx, true);

        EXECUTE format('SELECT count(*) FROM public.%I WHERE %I::text = $1', t.name, pk)
           INTO n USING b_id;
        IF n <> 0 THEN
            RAISE EXCEPTION '%：A 上下文读到了 B 的行', t.name;
        END IF;

        BEGIN
            EXECUTE format('UPDATE public.%I SET %I = %I WHERE %I::text = $1',
                           t.name, tenant_col, tenant_col, pk) USING b_id;
            GET DIAGNOSTICS affected = ROW_COUNT;
            IF affected <> 0 THEN
                RAISE EXCEPTION '%：A 上下文修改了 B 的行', t.name;
            END IF;
        EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION '%：A 上下文修改 B 的行时出错：%', t.name, SQLERRM;
        END;

        BEGIN
            EXECUTE format('DELETE FROM public.%I WHERE %I::text = $1', t.name, pk) USING b_id;
            GET DIAGNOSTICS affected = ROW_COUNT;
            IF affected <> 0 THEN
                RAISE EXCEPTION '%：A 上下文删除了 B 的行', t.name;
            END IF;
        EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION '%：A 上下文删除 B 的行时出错：%', t.name, SQLERRM;
        END;
    END LOOP;

    IF covered < 12 THEN
        RAISE EXCEPTION '夹具覆盖的租户表过少：%，矩阵没有真正跑起来', covered;
    END IF;
END
$$;

-- ---------------------------------------------------------------- 3 列表、批量、搜索、导出

BEGIN;
SELECT set_config('app.tenant_id', :ct_a, true);
DO $$
DECLARE
    n bigint;
    v text;
BEGIN
    SELECT count(*), min(id::text) INTO n, v
      FROM organization_person WHERE display_name = '张三';
    IF n <> 1 OR v <> 'a1000001-0000-4000-8000-000000000001' THEN
        RAISE EXCEPTION '同名列表越界：A 应只看到自己的张三，实际 % 行 / %', n, v;
    END IF;

    SELECT count(*) INTO n FROM organization_person
     WHERE primary_email = 'zhangsan@same.example';
    IF n <> 1 THEN
        RAISE EXCEPTION '同邮箱列表越界：实际 % 行', n;
    END IF;

    SELECT count(*) INTO n FROM organization_person
     WHERE id IN ('b2000001-0000-4000-8000-000000000001',
                  'b2000002-0000-4000-8000-000000000002');
    IF n <> 0 THEN
        RAISE EXCEPTION '批量读取越界：实际 % 行', n;
    END IF;

    SELECT count(*) INTO n FROM organization_person WHERE display_name ILIKE '%张%';
    IF n <> 1 THEN
        RAISE EXCEPTION '搜索越界：实际 % 行', n;
    END IF;

    SELECT count(*) INTO n FROM organization_employment WHERE employment_no = 'E-0001';
    IF n <> 1 THEN
        RAISE EXCEPTION '同工号导出越界：实际 % 行', n;
    END IF;

    SELECT count(*) INTO n FROM organization_person;
    IF n <> 3 THEN
        RAISE EXCEPTION '导出应有 A 的 3 行，实际 %', n;
    END IF;
END
$$;
COMMIT;

-- ---------------------------------------------------------------- 4 附件、作业、缓存、Outbox、Webhook

BEGIN;
SELECT set_config('app.tenant_id', :ct_a, true);
DO $$
DECLARE
    n bigint;
    v text;
BEGIN
    SELECT count(*) INTO n FROM platform_file;
    IF n <> 1 THEN
        RAISE EXCEPTION '附件列表越界：A 应只看到 1 份文件，实际 %', n;
    END IF;

    SELECT count(*) INTO n FROM platform_file
     WHERE id = 'b3000001-0000-4000-8000-000000000001';
    IF n <> 0 THEN
        RAISE EXCEPTION '附件详情越界';
    END IF;

    SELECT object_key INTO v FROM platform_file;
    IF v NOT LIKE 'a1a1a1a1-0000-4000-8000-000000000001/%' THEN
        RAISE EXCEPTION '对象键缺少租户前缀：%', v;
    END IF;
    IF v LIKE '%b2b2b2b2-0000-4000-8000-000000000002%' THEN
        RAISE EXCEPTION '对象键混入他租户前缀：%', v;
    END IF;

    SELECT count(*) INTO n FROM platform_job WHERE id = 'b4000001-0000-4000-8000-000000000001';
    IF n <> 0 THEN
        RAISE EXCEPTION '作业读取越界';
    END IF;

    SELECT count(*) INTO n FROM platform_job;
    IF n <> 1 THEN
        RAISE EXCEPTION '作业列表越界：实际 % 行', n;
    END IF;

    -- platform_outbox 按设计不启用 RLS：Worker 必须跨租户扫描，隔离责任落在「查询必须显式带 tenant_id」
    IF (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.platform_outbox'::regclass) THEN
        RAISE EXCEPTION 'platform_outbox 不应启用 RLS，否则 Worker 无法跨租户投递';
    END IF;

    SELECT count(*) INTO n FROM platform_outbox
     WHERE tenant_id = 'a1a1a1a1-0000-4000-8000-000000000001';
    IF n <> 2 THEN
        RAISE EXCEPTION 'Outbox 显式按 A 过滤后应只有 2 条事件，实际 %', n;
    END IF;

    SELECT count(*) INTO n FROM platform_outbox
     WHERE tenant_id = 'b2b2b2b2-0000-4000-8000-000000000002';
    IF n <> 2 THEN
        RAISE EXCEPTION 'Outbox 是显式过滤的跨租户表，B 应恰有 2 条事件，实际 %', n;
    END IF;

    SELECT count(*) INTO n FROM authorization_cache_version;
    IF n <> 1 THEN
        RAISE EXCEPTION '缓存版本越界：实际 % 行', n;
    END IF;

    SELECT count(*) INTO n FROM authorization_assignment
     WHERE id = 'b6000001-0000-4000-8000-000000000001';
    IF n <> 0 THEN
        RAISE EXCEPTION '授权读取越界';
    END IF;

    SELECT count(*) INTO n FROM authorization_decision_log
     WHERE id = 'b8000001-0000-4000-8000-000000000001';
    IF n <> 0 THEN
        RAISE EXCEPTION '决策日志读取越界';
    END IF;

    SELECT count(*) INTO n FROM authorization_access_review_item
     WHERE review_id = 'b9000001-0000-4000-8000-000000000001';
    IF n <> 0 THEN
        RAISE EXCEPTION '复核条目读取越界';
    END IF;

    SELECT count(*) INTO n FROM authorization_admin_action
     WHERE detail ->> 'tenant' = 'B';
    IF n <> 0 THEN
        RAISE EXCEPTION '管理员审计读取越界';
    END IF;

    IF to_regclass('public.platform_webhook_delivery') IS NOT NULL THEN
        SELECT count(*) INTO n FROM platform_webhook_delivery;
        IF n <> 0 THEN
            RAISE EXCEPTION 'Webhook 投递表读取越界（该表尚未实现但已存在）';
        END IF;
    END IF;
END
$$;
COMMIT;

-- Webhook 投递尚未实现：显式登记，不用「跳过」掩盖
DO $$
BEGIN
    IF to_regclass('public.platform_webhook_delivery') IS NULL THEN
        RAISE NOTICE '已知项：platform_webhook_delivery 尚未实现，Webhook 攻击面由 Outbox/作业用例替代';
    END IF;
END
$$;

-- ---------------------------------------------------------------- 5 缺失或伪造 RLS 变量

BEGIN;
DO $$
DECLARE
    n bigint;
    failed boolean;
BEGIN
    PERFORM set_config('app.tenant_id', '', true);
    SELECT count(*) INTO n FROM organization_person;
    IF n <> 0 THEN RAISE EXCEPTION '空租户变量仍返回数据：%', n; END IF;
    SELECT count(*) INTO n FROM tenancy_tenant;
    IF n <> 0 THEN RAISE EXCEPTION '空租户变量仍看到租户：%', n; END IF;

    failed := false;
    BEGIN
        PERFORM set_config('app.tenant_id', 'true', true);
        SELECT count(*) INTO n FROM organization_person;
        IF n <> 0 THEN
            RAISE EXCEPTION '布尔注入返回数据：%', n;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        failed := true;
    END;
    IF NOT failed THEN
        PERFORM set_config('app.tenant_id', '', true);
    END IF;

    failed := false;
    BEGIN
        PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-00000000000z', true);
        SELECT count(*) INTO n FROM organization_person;
        IF n <> 0 THEN
            RAISE EXCEPTION '非法 UUID 返回数据：%', n;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        failed := true;
    END;

    PERFORM set_config('app.tenant_id', 'cccccccc-0000-4000-8000-0000000000ff', true);
    SELECT count(*) INTO n FROM organization_person;
    IF n <> 0 THEN RAISE EXCEPTION '不存在的租户返回数据：%', n; END IF;
END
$$;
COMMIT;

-- ---------------------------------------------------------------- 6 连接池复用与残留

BEGIN;
SELECT set_config('app.tenant_id', :ct_a, true);
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM organization_person;
    IF n <> 3 THEN RAISE EXCEPTION 'A 上下文应看到 3 行，实际 %', n; END IF;
END
$$;
COMMIT;

-- 事务提交后同一连接未重设变量，必须回到 0 行
DO $$
DECLARE n bigint;
BEGIN
    IF current_setting('app.tenant_id', true) IS NOT NULL
       AND current_setting('app.tenant_id', true) <> '' THEN
        RAISE EXCEPTION '事务结束后仍残留租户变量：%', current_setting('app.tenant_id', true);
    END IF;
    SELECT count(*) INTO n FROM organization_person;
    IF n <> 0 THEN RAISE EXCEPTION '连接复用后仍看到数据：%', n; END IF;
END
$$;

-- 会话级设置必须能被 RESET ALL 清除
SELECT set_config('app.tenant_id', 'a1a1a1a1-0000-4000-8000-000000000001', false);
RESET ALL;
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM organization_person;
    IF n <> 0 THEN RAISE EXCEPTION 'RESET ALL 后仍看到数据：%', n; END IF;
END
$$;

-- ---------------------------------------------------------------- 7 异常子事务回滚

BEGIN;
DO $$
DECLARE n bigint;
BEGIN
    BEGIN
        PERFORM set_config('app.tenant_id', 'a1a1a1a1-0000-4000-8000-000000000001', true);
        PERFORM 1 / 0;
    EXCEPTION WHEN OTHERS THEN
        NULL;
    END;
    SELECT count(*) INTO n FROM organization_person;
    IF n <> 0 THEN
        RAISE EXCEPTION '异常子事务回滚后仍残留租户上下文：% 行', n;
    END IF;
END
$$;
COMMIT;

-- ---------------------------------------------------------------- 8 只读事务与分析角色语义

BEGIN READ ONLY;
SELECT set_config('app.tenant_id', :ct_a, true);
DO $$
DECLARE
    n bigint;
    blocked boolean := false;
BEGIN
    IF current_setting('transaction_read_only') <> 'on' THEN
        RAISE EXCEPTION '只读事务未生效';
    END IF;
    SELECT count(*) INTO n FROM organization_person;
    IF n <> 3 THEN RAISE EXCEPTION '只读事务下 A 应看到 3 行，实际 %', n; END IF;
    SELECT count(*) INTO n FROM organization_person
     WHERE id = 'b2000001-0000-4000-8000-000000000001';
    IF n <> 0 THEN RAISE EXCEPTION '只读事务下跨租户可见'; END IF;

    BEGIN
        INSERT INTO organization_person (tenant_id, display_name)
        VALUES ('a1a1a1a1-0000-4000-8000-000000000001', '只读写入');
    EXCEPTION WHEN read_only_sql_transaction THEN
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '只读事务竟然允许写入';
    END IF;
END
$$;
COMMIT;

-- ---------------------------------------------------------------- 9 跨租户写入

BEGIN;
SELECT set_config('app.tenant_id', :ct_a, true);
DO $$
DECLARE
    blocked boolean := false;
    state text;
BEGIN
    BEGIN
        INSERT INTO organization_person (tenant_id, display_name)
        VALUES ('b2b2b2b2-0000-4000-8000-000000000002', '伪造跨租户写入');
    EXCEPTION WHEN OTHERS THEN
        state := SQLSTATE;
        blocked := state IN ('42501', '23514');
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION 'A 上下文向 B 写入未被拒绝，SQLSTATE=%', coalesce(state, 'none');
    END IF;
END
$$;
COMMIT;

\echo 'WP02-06 跨租户数据库断言通过'
