-- V7 身份与会话：User / TenantMember / Actor / Person / Employment 分离模型。
--
-- 边界：
-- 1. identity 只保存登录身份、成员关系、会话、MFA、令牌与服务账号，不保存员工业务档案；
-- 2. Person（自然人）与 Employment（一段雇佣关系）在 organization 模块，支持无账号员工、
--    一人多雇佣与再雇佣；登录账号通过 tenant_member 与 Person 关联，可更换而不丢历史；
-- 3. 所有租户表照抄 backend/db/templates/tenant-table.template.sql：ENABLE + FORCE RLS，
--    策略统一为 tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid。
--
-- 认证查询（会话/令牌按哈希查找）同样在租户事务内执行：调用方先用请求的租户 ID
-- 建立 TenantTransactions 上下文，因此把 A 租户的令牌发到 B 租户时天然返回 0 行，
-- 不需要额外的跨租户黑名单。

CREATE TABLE organization_person (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    display_name  text        NOT NULL,
    primary_email text,
    status        text        NOT NULL DEFAULT 'active',
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_person_status_check CHECK (status IN ('active', 'inactive')),
    CONSTRAINT organization_person_name_check CHECK (length(btrim(display_name)) BETWEEN 1 AND 200)
);

COMMENT ON TABLE organization_person IS
    '自然人：不等同于登录账号，允许无账号员工；登录身份由 identity_tenant_member 关联';
COMMENT ON COLUMN organization_person.primary_email IS
    '联系用邮箱，可为空；登录邮箱在 identity_user_account，二者不要求一致';

CREATE INDEX organization_person_tenant_idx ON organization_person (tenant_id, created_at DESC);

ALTER TABLE organization_person ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_person FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_person_isolation ON organization_person
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE organization_employment (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenancy_tenant (id),
    person_id      uuid        NOT NULL REFERENCES organization_person (id),
    employment_no  text        NOT NULL,
    status         text        NOT NULL DEFAULT 'active',
    effective_from date        NOT NULL,
    effective_to   date,
    job_title      text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_employment_status_check CHECK (status IN ('active', 'ended')),
    CONSTRAINT organization_employment_number_check
        CHECK (length(btrim(employment_no)) BETWEEN 1 AND 64),
    CONSTRAINT organization_employment_period_check
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT organization_employment_number_unique UNIQUE (tenant_id, employment_no)
);

COMMENT ON TABLE organization_employment IS
    '一段雇佣关系：同一 Person 可有多条 active 记录（一人多雇佣）；再雇佣是新开一段而不是复用旧行';
COMMENT ON COLUMN organization_employment.employment_no IS
    '租户内唯一的雇佣编号；WP02-03 接入法人后仍保持该唯一性';

CREATE INDEX organization_employment_tenant_idx ON organization_employment (tenant_id, created_at DESC);
CREATE INDEX organization_employment_person_idx ON organization_employment (tenant_id, person_id, effective_from DESC);

ALTER TABLE organization_employment ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_employment FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_employment_isolation ON organization_employment
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_user_account (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    email            text        NOT NULL,
    email_normalized text        NOT NULL,
    display_name     text        NOT NULL,
    password_hash    text,
    status           text        NOT NULL DEFAULT 'invited',
    auth_version     integer     NOT NULL DEFAULT 1,
    mfa_required     boolean     NOT NULL DEFAULT false,
    failed_attempts  integer     NOT NULL DEFAULT 0,
    locked_until     timestamptz,
    last_login_at    timestamptz,
    activated_at     timestamptz,
    disabled_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_user_account_status_check
        CHECK (status IN ('invited', 'active', 'suspended', 'disabled')),
    CONSTRAINT identity_user_account_email_check
        CHECK (length(btrim(email_normalized)) BETWEEN 3 AND 320),
    CONSTRAINT identity_user_account_auth_version_check CHECK (auth_version >= 1),
    CONSTRAINT identity_user_account_failed_attempts_check CHECK (failed_attempts >= 0),
    CONSTRAINT identity_user_account_email_unique UNIQUE (tenant_id, email_normalized)
);

COMMENT ON TABLE identity_user_account IS
    '登录身份：只存认证所需字段，员工档案在 organization_person/employment';
COMMENT ON COLUMN identity_user_account.password_hash IS
    'BCrypt 自适应哈希；invited 状态允许为空，激活时必须写入；任何明文或可逆编码都不允许入库';
COMMENT ON COLUMN identity_user_account.auth_version IS
    '会话代际：禁用、重置密码、全端登出时 +1，旧会话在下一次校验时立即失效';
COMMENT ON COLUMN identity_user_account.failed_attempts IS
    '连续失败次数，达到阈值后写 locked_until；成功登录清零';

CREATE INDEX identity_user_account_tenant_idx ON identity_user_account (tenant_id, created_at DESC);
CREATE INDEX identity_user_account_status_idx ON identity_user_account (tenant_id, status);

ALTER TABLE identity_user_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_user_account FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_user_account_isolation ON identity_user_account
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_actor (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenancy_tenant (id),
    actor_type   text        NOT NULL,
    display_name text        NOT NULL,
    status       text        NOT NULL DEFAULT 'active',
    user_id      uuid        REFERENCES identity_user_account (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_actor_type_check CHECK (actor_type IN ('user', 'service', 'ai', 'external')),
    CONSTRAINT identity_actor_status_check CHECK (status IN ('active', 'disabled')),
    CONSTRAINT identity_actor_type_user_check CHECK (actor_type <> 'user' OR user_id IS NOT NULL)
);

COMMENT ON TABLE identity_actor IS
    '统一主体：用户、服务账号、AI 与外部协作者共用同一套标识，行为都可追责';
COMMENT ON COLUMN identity_actor.user_id IS
    'actor_type=user 时必须归属一个登录账号；服务账号由 service_account.actor_id 反向引用';

CREATE UNIQUE INDEX identity_actor_user_unique
    ON identity_actor (tenant_id, user_id)
    WHERE user_id IS NOT NULL;
CREATE INDEX identity_actor_tenant_type_idx ON identity_actor (tenant_id, actor_type, status);

ALTER TABLE identity_actor ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_actor FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_actor_isolation ON identity_actor
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_service_account (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    actor_id    uuid        NOT NULL REFERENCES identity_actor (id),
    name        text        NOT NULL,
    description text,
    status      text        NOT NULL DEFAULT 'active',
    created_by  text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_service_account_status_check CHECK (status IN ('active', 'disabled')),
    CONSTRAINT identity_service_account_name_check
        CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT identity_service_account_name_unique UNIQUE (tenant_id, name),
    CONSTRAINT identity_service_account_actor_unique UNIQUE (tenant_id, actor_id)
);

COMMENT ON TABLE identity_service_account IS
    '服务账号：只用于机器调用，不能登录；凭据是短期 API 令牌，不是密码';

CREATE INDEX identity_service_account_tenant_idx ON identity_service_account (tenant_id, created_at DESC);

ALTER TABLE identity_service_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_service_account FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_service_account_isolation ON identity_service_account
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_tenant_member (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    user_id       uuid        NOT NULL REFERENCES identity_user_account (id),
    person_id     uuid        REFERENCES organization_person (id),
    status        text        NOT NULL DEFAULT 'invited',
    is_owner      boolean     NOT NULL DEFAULT false,
    is_admin      boolean     NOT NULL DEFAULT false,
    mfa_required  boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_tenant_member_status_check
        CHECK (status IN ('invited', 'active', 'suspended', 'disabled')),
    CONSTRAINT identity_tenant_member_user_unique UNIQUE (tenant_id, user_id)
);

COMMENT ON TABLE identity_tenant_member IS
    '租户成员关系：账号与自然人/雇佣解耦，换账号只更新 user_id 并写 account_history';
COMMENT ON COLUMN identity_tenant_member.mfa_required IS
    '高权限成员强制 MFA；为真时未完成第二因子的会话不能执行安全配置类动作';

CREATE UNIQUE INDEX identity_tenant_member_person_unique
    ON identity_tenant_member (tenant_id, person_id)
    WHERE person_id IS NOT NULL;
CREATE INDEX identity_tenant_member_tenant_status_idx ON identity_tenant_member (tenant_id, status);

ALTER TABLE identity_tenant_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_tenant_member FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_tenant_member_isolation ON identity_tenant_member
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_member_account_history (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    member_id        uuid        NOT NULL REFERENCES identity_tenant_member (id),
    previous_user_id uuid        NOT NULL REFERENCES identity_user_account (id),
    new_user_id      uuid        NOT NULL REFERENCES identity_user_account (id),
    changed_by       text        NOT NULL,
    reason           text        NOT NULL,
    changed_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_member_account_history_reason_check
        CHECK (length(btrim(reason)) > 0)
);

COMMENT ON TABLE identity_member_account_history IS
    '账号更换审计：旧账号必须停用，历史保留旧/新账号与操作原因';

CREATE INDEX identity_member_account_history_member_idx
    ON identity_member_account_history (tenant_id, member_id, changed_at DESC);

ALTER TABLE identity_member_account_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_member_account_history FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_member_account_history_isolation ON identity_member_account_history
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_password_history (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    user_id       uuid        NOT NULL REFERENCES identity_user_account (id),
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE identity_password_history IS
    '密码历史：只保留哈希，用于禁止复用最近 5 个密码';

CREATE INDEX identity_password_history_user_idx
    ON identity_password_history (tenant_id, user_id, created_at DESC);

ALTER TABLE identity_password_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_password_history FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_password_history_isolation ON identity_password_history
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_invitation (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    user_id     uuid        NOT NULL REFERENCES identity_user_account (id),
    member_id   uuid        NOT NULL REFERENCES identity_tenant_member (id),
    email       text        NOT NULL,
    token_hash  text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    accepted_at timestamptz,
    revoked_at  timestamptz,
    created_by  text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_invitation_token_check CHECK (length(token_hash) = 64),
    CONSTRAINT identity_invitation_token_unique UNIQUE (token_hash)
);

COMMENT ON TABLE identity_invitation IS
    '邀请/激活：令牌只存 SHA-256，一次性、默认 72 小时过期；P0 由创建接口返回明文，P1 改为邮件投递';
COMMENT ON COLUMN identity_invitation.email IS
    '邀请时的邮箱快照：接受时必须与 user_account.email_normalized 一致';

CREATE INDEX identity_invitation_tenant_idx ON identity_invitation (tenant_id, created_at DESC);

ALTER TABLE identity_invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_invitation FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_invitation_isolation ON identity_invitation
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_mfa_method (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenancy_tenant (id),
    user_id           uuid        NOT NULL REFERENCES identity_user_account (id),
    method_type       text        NOT NULL DEFAULT 'totp',
    secret_ciphertext text        NOT NULL,
    secret_nonce      text        NOT NULL,
    status            text        NOT NULL DEFAULT 'pending',
    confirmed_at      timestamptz,
    last_used_step    bigint,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_mfa_method_type_check CHECK (method_type IN ('totp')),
    CONSTRAINT identity_mfa_method_status_check CHECK (status IN ('pending', 'active', 'disabled'))
);

COMMENT ON TABLE identity_mfa_method IS
    'MFA 方式：TOTP 密钥使用 AES-256-GCM 加密后入库，密钥来自平台环境变量';
COMMENT ON COLUMN identity_mfa_method.last_used_step IS
    '最近一次通过的时间步，重复提交同一时间步必须拒绝，防止重放';

CREATE UNIQUE INDEX identity_mfa_method_active_unique
    ON identity_mfa_method (tenant_id, user_id, method_type)
    WHERE status <> 'disabled';

ALTER TABLE identity_mfa_method ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_method FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_mfa_method_isolation ON identity_mfa_method
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_recovery_code (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenancy_tenant (id),
    user_id    uuid        NOT NULL REFERENCES identity_user_account (id),
    code_hash  text        NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_recovery_code_hash_check CHECK (length(code_hash) = 64),
    CONSTRAINT identity_recovery_code_unique UNIQUE (tenant_id, user_id, code_hash)
);

COMMENT ON TABLE identity_recovery_code IS
    'MFA 恢复码：只存哈希，一次性；10 条用完后必须重新生成';

CREATE INDEX identity_recovery_code_user_idx ON identity_recovery_code (tenant_id, user_id, created_at DESC);

ALTER TABLE identity_recovery_code ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_recovery_code FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_recovery_code_isolation ON identity_recovery_code
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_mfa_challenge (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    user_id     uuid        NOT NULL REFERENCES identity_user_account (id),
    token_hash  text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    attempts    integer     NOT NULL DEFAULT 0,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_mfa_challenge_hash_check CHECK (length(token_hash) = 64),
    CONSTRAINT identity_mfa_challenge_attempts_check CHECK (attempts BETWEEN 0 AND 10),
    CONSTRAINT identity_mfa_challenge_token_unique UNIQUE (token_hash)
);

COMMENT ON TABLE identity_mfa_challenge IS
    '登录第二因子短期票据：5 分钟有效、最多 10 次尝试、成功即消费';

CREATE INDEX identity_mfa_challenge_tenant_idx ON identity_mfa_challenge (tenant_id, expires_at);

ALTER TABLE identity_mfa_challenge ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_challenge FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_mfa_challenge_isolation ON identity_mfa_challenge
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_session (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    user_id         uuid        NOT NULL REFERENCES identity_user_account (id),
    actor_id        uuid        NOT NULL REFERENCES identity_actor (id),
    token_hash      text        NOT NULL,
    auth_version    integer     NOT NULL,
    mfa_satisfied   boolean     NOT NULL DEFAULT false,
    device_id       uuid,
    device_label    text,
    ip              text,
    user_agent      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now(),
    idle_expires_at timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    revoked_reason  text,
    revoked_by      text,
    CONSTRAINT identity_session_hash_check CHECK (length(token_hash) = 64),
    CONSTRAINT identity_session_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT identity_session_token_unique UNIQUE (token_hash)
);

COMMENT ON TABLE identity_session IS
    '浏览器/设备会话：令牌只存哈希；校验时联查 user_account 的 status 与 auth_version';
COMMENT ON COLUMN identity_session.device_id IS
    '同一设备的多条会话共享该 ID，撤销设备即撤销该设备全部会话';

CREATE INDEX identity_session_user_idx ON identity_session (tenant_id, user_id, created_at DESC);
CREATE INDEX identity_session_device_idx ON identity_session (tenant_id, device_id);

ALTER TABLE identity_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_session FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_session_isolation ON identity_session
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_api_token (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenancy_tenant (id),
    service_account_id uuid        NOT NULL REFERENCES identity_service_account (id),
    actor_id           uuid        NOT NULL REFERENCES identity_actor (id),
    name               text        NOT NULL,
    token_hash         text        NOT NULL,
    prefix             text        NOT NULL,
    scopes             text[]      NOT NULL DEFAULT '{}',
    expires_at         timestamptz NOT NULL,
    revoked_at         timestamptz,
    revoked_by         text,
    last_used_at       timestamptz,
    created_by         text        NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_api_token_hash_check CHECK (length(token_hash) = 64),
    CONSTRAINT identity_api_token_prefix_check CHECK (length(prefix) BETWEEN 4 AND 16),
    CONSTRAINT identity_api_token_name_check CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT identity_api_token_token_unique UNIQUE (token_hash)
);

COMMENT ON TABLE identity_api_token IS
    '服务账号 API 令牌：只存哈希与前缀，明文仅在创建响应出现一次；最长 90 天';
COMMENT ON COLUMN identity_api_token.scopes IS
    '令牌声明的能力范围，WP02-04 接入统一授权后由策略引擎消费；当前只做存储与展示';

CREATE INDEX identity_api_token_account_idx
    ON identity_api_token (tenant_id, service_account_id, created_at DESC);

ALTER TABLE identity_api_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_api_token FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_api_token_isolation ON identity_api_token
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_identity_provider (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    provider_type text        NOT NULL,
    name          text        NOT NULL,
    config        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status        text        NOT NULL DEFAULT 'draft',
    created_by    text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_identity_provider_type_check
        CHECK (provider_type IN ('oidc', 'saml', 'scim')),
    CONSTRAINT identity_identity_provider_status_check
        CHECK (status IN ('draft', 'disabled', 'active')),
    CONSTRAINT identity_identity_provider_name_check
        CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT identity_identity_provider_config_check
        CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT identity_identity_provider_unique UNIQUE (tenant_id, provider_type, name)
);

COMMENT ON TABLE identity_identity_provider IS
    'OIDC/SAML/SCIM 预留模型：P0 只允许 draft/disabled；active 由 WP09 的连接器落地后才会开放';
COMMENT ON COLUMN identity_identity_provider.config IS
    '连接器配置；P0 不保存客户端密钥明文，接入时改由秘密存储引用';

ALTER TABLE identity_identity_provider ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_identity_provider FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_identity_provider_isolation ON identity_identity_provider
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE identity_auth_event (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenancy_tenant (id),
    actor_id       uuid,
    user_id        uuid,
    event_type     text        NOT NULL,
    outcome        text        NOT NULL,
    ip             text,
    user_agent     text,
    detail         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    correlation_id text,
    trace_id       text,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_auth_event_outcome_check CHECK (outcome IN ('success', 'failure')),
    CONSTRAINT identity_auth_event_type_check
        CHECK (event_type IN ('login', 'mfa_verify', 'logout', 'session_revoke',
                              'password_change', 'password_reset', 'invitation_accept',
                              'member_disable', 'token_create', 'token_revoke', 'mfa_disable'))
);

COMMENT ON TABLE identity_auth_event IS
    '认证审计：登录、MFA、登出、撤销、密码与令牌动作；只记 ID 与结果，不记密码或令牌明文';

CREATE INDEX identity_auth_event_tenant_idx ON identity_auth_event (tenant_id, occurred_at DESC);
CREATE INDEX identity_auth_event_user_idx ON identity_auth_event (tenant_id, user_id, occurred_at DESC);

ALTER TABLE identity_auth_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_auth_event FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_auth_event_isolation ON identity_auth_event
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
