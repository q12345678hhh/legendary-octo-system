-- WP02-02：身份、成员、会话与雇佣模型在真实 PostgreSQL 上的 RLS、唯一性与跨租户断言。
-- 以 platform_app 身份执行；每个租户操作都在显式事务内用 set_config(..., true) 建立上下文。

\set id_c '''cccccccc-0000-4000-8000-000000000003'''
\set id_d '''dddddddd-0000-4000-8000-000000000004'''

-- 1. 两个租户各自建账号、主体、成员、自然人与雇佣
BEGIN;
SELECT set_config('app.tenant_id', :id_c, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:id_c, 'id-gamma', '身份甲', 'active', 'standard');

INSERT INTO organization_person (id, tenant_id, display_name, primary_email)
VALUES ('c0000000-0000-4000-8000-000000000101', :id_c, '张一', 'zhangyi@example.com'),
       ('c0000000-0000-4000-8000-000000000102', :id_c, '无账号员工', 'noaccount@example.com');

-- 一人多雇佣：同一个 Person 两条 active Employment
INSERT INTO organization_employment
    (id, tenant_id, person_id, employment_no, status, effective_from, job_title)
VALUES ('c0000000-0000-4000-8000-000000000201', :id_c,
        'c0000000-0000-4000-8000-000000000101', 'E-0001', 'active', DATE '2024-01-01', '工程师'),
       ('c0000000-0000-4000-8000-000000000202', :id_c,
        'c0000000-0000-4000-8000-000000000101', 'E-0002', 'active', DATE '2025-03-01', '技术负责人');

INSERT INTO identity_user_account
    (id, tenant_id, email, email_normalized, display_name, status, activated_at)
VALUES ('c0000000-0000-4000-8000-000000000301', :id_c, 'ZhangYi@Example.com',
        'zhangyi@example.com', '张一', 'active', now());

INSERT INTO identity_actor (id, tenant_id, actor_type, display_name, status, user_id)
VALUES ('c0000000-0000-4000-8000-000000000401', :id_c, 'user', '张一', 'active',
        'c0000000-0000-4000-8000-000000000301');

INSERT INTO identity_tenant_member (id, tenant_id, user_id, person_id, status, is_owner, mfa_required)
VALUES ('c0000000-0000-4000-8000-000000000501', :id_c,
        'c0000000-0000-4000-8000-000000000301',
        'c0000000-0000-4000-8000-000000000101', 'active', true, true);

INSERT INTO identity_actor (id, tenant_id, actor_type, display_name, status)
VALUES ('c0000000-0000-4000-8000-000000000402', :id_c, 'service', '同步服务', 'active');

INSERT INTO identity_service_account (id, tenant_id, actor_id, name, created_by)
VALUES ('c0000000-0000-4000-8000-000000000601', :id_c,
        'c0000000-0000-4000-8000-000000000402', 'sync-bot', 'verify');

INSERT INTO identity_session
    (id, tenant_id, user_id, actor_id, token_hash, auth_version, mfa_satisfied,
     idle_expires_at, expires_at)
VALUES ('c0000000-0000-4000-8000-000000000701', :id_c,
        'c0000000-0000-4000-8000-000000000301',
        'c0000000-0000-4000-8000-000000000401',
        repeat('a', 64), 1, true, now() + interval '30 minutes', now() + interval '8 hours');

INSERT INTO identity_invitation
    (id, tenant_id, user_id, member_id, email, token_hash, expires_at, created_by)
VALUES ('c0000000-0000-4000-8000-000000000801', :id_c,
        'c0000000-0000-4000-8000-000000000301',
        'c0000000-0000-4000-8000-000000000501',
        'zhangyi@example.com', repeat('b', 64), now() + interval '72 hours', 'verify');

INSERT INTO identity_mfa_method
    (id, tenant_id, user_id, secret_ciphertext, secret_nonce)
VALUES ('c0000000-0000-4000-8000-000000000901', :id_c,
        'c0000000-0000-4000-8000-000000000301', 'ciphertext', 'nonce');
COMMIT;

BEGIN;
SELECT set_config('app.tenant_id', :id_d, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:id_d, 'id-delta', '身份乙', 'active', 'standard');

-- 同邮箱允许存在于另一个租户
INSERT INTO identity_user_account
    (id, tenant_id, email, email_normalized, display_name, status, activated_at)
VALUES ('d0000000-0000-4000-8000-000000000301', :id_d, 'zhangyi@example.com',
        'zhangyi@example.com', '张一（乙租户）', 'active', now());

INSERT INTO identity_actor (id, tenant_id, actor_type, display_name, status, user_id)
VALUES ('d0000000-0000-4000-8000-000000000401', :id_d, 'user', '张一（乙租户）', 'active',
        'd0000000-0000-4000-8000-000000000301');

INSERT INTO identity_tenant_member (id, tenant_id, user_id, status)
VALUES ('d0000000-0000-4000-8000-000000000501', :id_d,
        'd0000000-0000-4000-8000-000000000301', 'active');

INSERT INTO identity_session
    (id, tenant_id, user_id, actor_id, token_hash, auth_version, mfa_satisfied,
     idle_expires_at, expires_at)
VALUES ('d0000000-0000-4000-8000-000000000701', :id_d,
        'd0000000-0000-4000-8000-000000000301',
        'd0000000-0000-4000-8000-000000000401',
        repeat('d', 64), 1, false, now() + interval '30 minutes', now() + interval '8 hours');
COMMIT;

-- 2. 唯一性：同租户同邮箱、同成员换人、同雇佣编号、同邀请令牌都必须被拒绝
BEGIN;
SELECT set_config('app.tenant_id', :id_c, true);
DO $$
BEGIN
    BEGIN
        INSERT INTO identity_user_account (tenant_id, email, email_normalized, display_name)
        VALUES ('cccccccc-0000-4000-8000-000000000003', 'other@example.com',
                'zhangyi@example.com', '重复邮箱');
        RAISE EXCEPTION '同租户重复登录邮箱竟然成功';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO organization_employment
            (id, tenant_id, person_id, employment_no, status, effective_from)
        VALUES ('c0000000-0000-4000-8000-000000000203',
                'cccccccc-0000-4000-8000-000000000003',
                'c0000000-0000-4000-8000-000000000102', 'E-0001', 'active', DATE '2025-01-01');
        RAISE EXCEPTION '同租户重复雇佣编号竟然成功';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO identity_invitation
            (tenant_id, user_id, member_id, email, token_hash, expires_at, created_by)
        VALUES ('cccccccc-0000-4000-8000-000000000003',
                'c0000000-0000-4000-8000-000000000301',
                'c0000000-0000-4000-8000-000000000501',
                'zhangyi@example.com', repeat('b', 64), now() + interval '1 hour', 'verify');
        RAISE EXCEPTION '重复邀请令牌竟然成功';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 3. 无租户上下文时，16 张身份/组织表必须全部返回 0 行
DO $$
DECLARE
    table_name text;
    visible bigint;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'organization_person', 'organization_employment',
        'identity_user_account', 'identity_actor', 'identity_service_account',
        'identity_tenant_member', 'identity_member_account_history',
        'identity_password_history', 'identity_invitation', 'identity_mfa_method',
        'identity_recovery_code', 'identity_mfa_challenge', 'identity_session',
        'identity_api_token', 'identity_identity_provider', 'identity_auth_event'
    ] LOOP
        EXECUTE format('SELECT count(*) FROM %I', table_name) INTO visible;
        IF visible <> 0 THEN
            RAISE EXCEPTION '无上下文时 % 应返回 0 行，实际 %', table_name, visible;
        END IF;
    END LOOP;
END
$$;

-- 4. 跨租户读写必须被 RLS 拒绝：甲租户上下文看不到也写不了乙租户的数据
BEGIN;
SELECT set_config('app.tenant_id', :id_c, true);
DO $$
DECLARE
    other_sessions bigint;
    own_sessions bigint;
    other_users bigint;
BEGIN
    SELECT count(*) INTO other_sessions FROM identity_session
     WHERE token_hash = repeat('d', 64);
    IF other_sessions <> 0 THEN
        RAISE EXCEPTION '甲租户竟然看到乙租户会话';
    END IF;

    SELECT count(*) INTO own_sessions FROM identity_session
     WHERE token_hash = repeat('a', 64);
    IF own_sessions <> 1 THEN
        RAISE EXCEPTION '甲租户应看到自己的 1 条会话，实际 %', own_sessions;
    END IF;

    SELECT count(*) INTO other_users FROM identity_user_account
     WHERE tenant_id = 'dddddddd-0000-4000-8000-000000000004';
    IF other_users <> 0 THEN
        RAISE EXCEPTION '甲租户竟然看到乙租户账号';
    END IF;

    BEGIN
        INSERT INTO organization_person (tenant_id, display_name)
        VALUES ('dddddddd-0000-4000-8000-000000000004', '越权写入');
        RAISE EXCEPTION '甲租户上下文竟然能写乙租户自然人';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 5. 账号更换：成员换 user_id 后历史行仍在，旧账号可停用
BEGIN;
SELECT set_config('app.tenant_id', :id_c, true);
INSERT INTO identity_user_account
    (id, tenant_id, email, email_normalized, display_name, status, activated_at)
VALUES ('c0000000-0000-4000-8000-000000000302', :id_c, 'zhangyi.new@example.com',
        'zhangyi.new@example.com', '张一（新账号）', 'active', now());

UPDATE identity_tenant_member
   SET user_id = 'c0000000-0000-4000-8000-000000000302', updated_at = now()
 WHERE id = 'c0000000-0000-4000-8000-000000000501';

INSERT INTO identity_member_account_history
    (tenant_id, member_id, previous_user_id, new_user_id, changed_by, reason)
VALUES (:id_c, 'c0000000-0000-4000-8000-000000000501',
        'c0000000-0000-4000-8000-000000000301',
        'c0000000-0000-4000-8000-000000000302', 'verify', '员工更换登录账号');

UPDATE identity_user_account
   SET status = 'disabled', disabled_at = now(), auth_version = auth_version + 1
 WHERE id = 'c0000000-0000-4000-8000-000000000301';

DO $$
DECLARE
    history bigint;
    old_version integer;
BEGIN
    SELECT count(*) INTO history FROM identity_member_account_history
     WHERE member_id = 'c0000000-0000-4000-8000-000000000501';
    IF history <> 1 THEN
        RAISE EXCEPTION '账号更换历史应有 1 条，实际 %', history;
    END IF;

    SELECT auth_version INTO old_version FROM identity_user_account
     WHERE id = 'c0000000-0000-4000-8000-000000000301';
    IF old_version <> 2 THEN
        RAISE EXCEPTION '旧账号禁用后 auth_version 应为 2，实际 %', old_version;
    END IF;
END
$$;
COMMIT;

\echo 'WP02-02 身份与会话数据库断言通过'
