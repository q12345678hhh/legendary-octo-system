-- RLS 正向与负例，以运行期账号 platform_app 执行。
-- 任何一条断言失败都会 RAISE EXCEPTION，psql 以非零码退出，CI 与本地脚本据此判定失败。
-- 租户 A: aaaaaaaa-0000-0000-0000-000000000001，租户 B: bbbbbbbb-0000-0000-0000-000000000002。

-- 用例 0：确认执行身份不是超级用户，否则整套用例会假绿
DO $$
BEGIN
    IF current_setting('is_superuser') <> 'off' THEN
        RAISE EXCEPTION '用例身份错误：platform_app 不能是超级用户，否则 RLS 被绕过';
    END IF;
    IF current_setting('row_security') <> 'on' THEN
        RAISE EXCEPTION 'row_security 必须为 on，实际 %', current_setting('row_security');
    END IF;
END
$$;

-- 用例 1：没有租户上下文时读不到任何行（默认拒绝，而不是报错）
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM tenancy_tenant;
    IF n <> 0 THEN
        RAISE EXCEPTION '无上下文时不应看到任何租户，实际 % 行', n;
    END IF;
END
$$;

-- 用例 2：SET LOCAL 后只能看到本租户
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001';
DO $$
DECLARE n bigint; v_slug text;
BEGIN
    SELECT count(*), min(t.slug) INTO n, v_slug FROM tenancy_tenant t;
    IF n <> 1 OR v_slug <> 'tenant-a' THEN
        RAISE EXCEPTION '上下文 A 应只看到 tenant-a 1 行，实际 % 行 / %', n, v_slug;
    END IF;
END
$$;
COMMIT;

-- 用例 3：伪造一个库里不存在的租户 ID 仍按空集处理，不泄露存在性
BEGIN;
SET LOCAL app.tenant_id = 'cccccccc-0000-0000-0000-000000000003';
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM tenancy_tenant;
    IF n <> 0 THEN
        RAISE EXCEPTION '伪造租户不应看到数据，实际 % 行', n;
    END IF;
END
$$;
COMMIT;

-- 用例 4：上下文为 A 时不能写入租户 B 的行（WITH CHECK 生效）
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001';
DO $$
BEGIN
    INSERT INTO tenancy_tenant (id, slug, name, status)
    VALUES ('bbbbbbbb-0000-0000-0000-000000000002', 'tenant-b-hijack', '越权租户 B', 'active');
    RAISE EXCEPTION '跨租户插入未被拒绝，RLS 失效';
EXCEPTION
    WHEN insufficient_privilege THEN
        NULL; -- 42501: new row violates row-level security policy，符合预期
END
$$;
COMMIT;

-- 用例 5：上下文为 A 时更新租户 B 的行影响 0 行
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001';
DO $$
DECLARE affected integer;
BEGIN
    UPDATE tenancy_tenant SET name = '被越权改名'
     WHERE id = 'bbbbbbbb-0000-0000-0000-000000000002';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 0 THEN
        RAISE EXCEPTION '跨租户更新影响 % 行，应为 0', affected;
    END IF;
END
$$;
COMMIT;

-- 用例 6：空串上下文（RESET 后的形态）返回 0 行而不是抛 invalid input syntax
BEGIN;
SELECT set_config('app.tenant_id', '', true);
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM tenancy_tenant;
    IF n <> 0 THEN
        RAISE EXCEPTION '空串上下文应返回 0 行，实际 %', n;
    END IF;
END
$$;
COMMIT;

-- 用例 7：连接池复用的核心断言——SET LOCAL 在事务提交后自动失效
BEGIN;
SET LOCAL app.tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001';
DO $$
BEGIN
    PERFORM 1 FROM tenancy_tenant LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION '事务内应能看到本租户数据';
    END IF;
END
$$;
COMMIT;
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM tenancy_tenant;
    IF n <> 0 THEN
        RAISE EXCEPTION '同一会话提交后仍能看到 % 行：租户上下文泄漏到下一个事务', n;
    END IF;
END
$$;

-- 用例 8：会话级 SET 会残留（反面样本），RESET 后恢复默认拒绝
SET app.tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001';
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM tenancy_tenant;
    IF n <> 1 THEN
        RAISE EXCEPTION '会话级 SET 后应看到 1 行，实际 %', n;
    END IF;
END
$$;
RESET app.tenant_id;
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM tenancy_tenant;
    IF n <> 0 THEN
        RAISE EXCEPTION 'RESET 后仍看到 % 行，重置失效', n;
    END IF;
END
$$;

-- 用例 9：运行期账号不能切换到迁移账号（防止运行期自己提权后绕过 RLS）
DO $$
BEGIN
    SET ROLE platform_owner;
    RAISE EXCEPTION '运行期账号不应能切换到 platform_owner';
EXCEPTION
    WHEN insufficient_privilege THEN
        NULL; -- 42501: permission denied to set role，符合预期
END
$$;

\echo 'RLS 用例全部通过（9/9）'
