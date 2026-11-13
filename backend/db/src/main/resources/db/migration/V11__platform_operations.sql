-- V11 平台运营端：独立运营账号、跨租户只读元数据、目录、安全事件、运行记录与设置。
--
-- 设计边界（与 PLAN §5.2 一致）：
--   1. 平台运营账号与租户身份是两个认证域，凭证、会话、Cookie 一律分开；
--   2. 平台域不获得 BYPASSRLS。跨租户可读的只有下面这些「元数据/聚合」表，
--      它们故意不启用 RLS（与 platform_outbox 同理），且字段白名单不允许出现业务正文；
--   3. 所有平台写操作都要落 platform_audit_event，且该表只能插入、不能改删。

-- ------------------------------------------------------------------ 运营账号

CREATE TABLE platform_operator (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email               text        NOT NULL,
    display_name        text        NOT NULL,
    role_code           text        NOT NULL,
    password_hash       text        NOT NULL,
    mfa_secret_cipher   text,
    mfa_nonce           text,
    mfa_confirmed       boolean     NOT NULL DEFAULT false,
    status              text        NOT NULL DEFAULT 'active',
    failed_attempts     integer     NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    last_login_at       timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_operator_email_key UNIQUE (email),
    CONSTRAINT platform_operator_email_lower_check CHECK (email = lower(email)),
    CONSTRAINT platform_operator_role_check
        CHECK (role_code IN ('platform-owner', 'platform-ops', 'platform-security', 'platform-auditor')),
    CONSTRAINT platform_operator_status_check CHECK (status IN ('active', 'disabled')),
    CONSTRAINT platform_operator_failed_attempts_check CHECK (failed_attempts >= 0),
    CONSTRAINT platform_operator_mfa_check
        CHECK (NOT mfa_confirmed OR (mfa_secret_cipher IS NOT NULL AND mfa_nonce IS NOT NULL))
);

COMMENT ON TABLE platform_operator IS
    '平台运营账号：与租户 identity_user_account 完全分离，不进入任何租户认证域';
COMMENT ON COLUMN platform_operator.mfa_secret_cipher IS
    'TOTP 密钥只存 AES-GCM 密文与 nonce，明文永不落库';
COMMENT ON COLUMN platform_operator.role_code IS
    '平台内置角色：owner/ops/security/auditor；租户不可见也不可修改';

CREATE TABLE platform_operator_session (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id   uuid        NOT NULL REFERENCES platform_operator (id) ON DELETE CASCADE,
    token_hash    text        NOT NULL,
    mfa_satisfied boolean     NOT NULL DEFAULT false,
    ip            text,
    user_agent    text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    idle_expires_at timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    revoked_at    timestamptz,
    revoked_reason text,
    CONSTRAINT platform_operator_session_token_key UNIQUE (token_hash),
    CONSTRAINT platform_operator_session_expiry_check CHECK (expires_at > created_at)
);

COMMENT ON TABLE platform_operator_session IS
    '平台运营会话：只存令牌 SHA-256；Cookie 名、Path 与租户会话不同，浏览器不会混发';

CREATE INDEX platform_operator_session_operator_idx
    ON platform_operator_session (operator_id, last_seen_at DESC);
CREATE INDEX platform_operator_session_expiry_idx
    ON platform_operator_session (expires_at);

-- ------------------------------------------------------------------ 平台审计

CREATE TABLE platform_audit_event (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id    uuid REFERENCES platform_operator (id),
    action         text        NOT NULL,
    target_type    text        NOT NULL,
    target_id      text,
    detail         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    correlation_id text,
    trace_id       text,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_audit_event_action_check CHECK (length(btrim(action)) > 0)
);

COMMENT ON TABLE platform_audit_event IS
    '平台级操作审计：只记录动作、对象与结果，不保存任何租户业务正文';

CREATE INDEX platform_audit_event_occurred_idx ON platform_audit_event (occurred_at DESC);
CREATE INDEX platform_audit_event_operator_idx ON platform_audit_event (operator_id, occurred_at DESC);

CREATE FUNCTION platform_audit_event_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'platform_audit_event 只允许插入，不允许修改或删除';
END
$$;

CREATE TRIGGER platform_audit_event_immutable_trigger
    BEFORE UPDATE OR DELETE ON platform_audit_event
    FOR EACH ROW EXECUTE FUNCTION platform_audit_event_immutable();

-- ------------------------------------------------------------------ 安全事件

CREATE TABLE platform_security_event (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid,
    category        text        NOT NULL,
    severity        text        NOT NULL,
    status          text        NOT NULL DEFAULT 'open',
    summary         text        NOT NULL,
    detail          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    acknowledged_by text,
    acknowledged_at timestamptz,
    resolved_by     text,
    resolved_at     timestamptz,
    CONSTRAINT platform_security_event_category_check
        CHECK (category IN ('abnormal_login', 'cross_tenant_blocked', 'secret_rotation',
                            'vulnerability', 'incident')),
    CONSTRAINT platform_security_event_severity_check
        CHECK (severity IN ('low', 'medium', 'high', 'critical')),
    CONSTRAINT platform_security_event_status_check
        CHECK (status IN ('open', 'acknowledged', 'resolved')),
    CONSTRAINT platform_security_event_summary_check CHECK (length(btrim(summary)) BETWEEN 4 AND 500),
    CONSTRAINT platform_security_event_ack_check
        CHECK (status = 'open' OR (acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL)),
    CONSTRAINT platform_security_event_resolve_check
        CHECK (status <> 'resolved' OR (resolved_by IS NOT NULL AND resolved_at IS NOT NULL))
);

COMMENT ON TABLE platform_security_event IS
    '安全事件台账：摘要与计数可以跨租户读，凭据、请求体与业务数据一律不入表';

CREATE INDEX platform_security_event_status_idx
    ON platform_security_event (status, occurred_at DESC);
CREATE INDEX platform_security_event_tenant_idx
    ON platform_security_event (tenant_id, occurred_at DESC);

-- ------------------------------------------------------------------ 跨租户只读元数据

CREATE TABLE platform_tenant_directory (
    id         uuid PRIMARY KEY,
    slug       text        NOT NULL,
    name       text        NOT NULL,
    status     text        NOT NULL,
    plan_code  text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

COMMENT ON TABLE platform_tenant_directory IS
    '租户目录投影：只同步生命周期与套餐等元数据，供平台列表与总览跨租户读取；'
    '品牌配置、配额明细、审计正文与任何业务数据都不在此表';

CREATE INDEX platform_tenant_directory_status_idx ON platform_tenant_directory (status);

CREATE FUNCTION platform_tenant_directory_sync() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO platform_tenant_directory (id, slug, name, status, plan_code, created_at, updated_at)
    VALUES (NEW.id, NEW.slug, NEW.name, NEW.status, NEW.plan_code, NEW.created_at, NEW.updated_at)
    ON CONFLICT (id) DO UPDATE
       SET slug = EXCLUDED.slug,
           name = EXCLUDED.name,
           status = EXCLUDED.status,
           plan_code = EXCLUDED.plan_code,
           updated_at = EXCLUDED.updated_at;
    RETURN NULL;
END
$$;

COMMENT ON FUNCTION platform_tenant_directory_sync() IS
    'tenancy_tenant 变更时同步目录投影：平台可见字段白名单只在这里定义一次';

CREATE TRIGGER tenancy_tenant_directory_sync
    AFTER INSERT OR UPDATE ON tenancy_tenant
    FOR EACH ROW EXECUTE FUNCTION platform_tenant_directory_sync();

INSERT INTO platform_tenant_directory (id, slug, name, status, plan_code, created_at, updated_at)
SELECT id, slug, name, status, plan_code, created_at, updated_at
  FROM tenancy_tenant
ON CONFLICT (id) DO NOTHING;

CREATE TABLE platform_tenant_metric_daily (
    tenant_id         uuid    NOT NULL,
    day               date    NOT NULL,
    active_users      integer NOT NULL DEFAULT 0,
    api_requests      bigint  NOT NULL DEFAULT 0,
    error_requests    bigint  NOT NULL DEFAULT 0,
    storage_bytes     bigint  NOT NULL DEFAULT 0,
    ai_tokens         bigint  NOT NULL DEFAULT 0,
    ai_cost_cents     bigint  NOT NULL DEFAULT 0,
    jobs_queued       integer NOT NULL DEFAULT 0,
    jobs_failed       integer NOT NULL DEFAULT 0,
    integration_errors integer NOT NULL DEFAULT 0,
    collected_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, day),
    CONSTRAINT platform_tenant_metric_daily_non_negative_check
        CHECK (active_users >= 0 AND api_requests >= 0 AND error_requests >= 0
               AND storage_bytes >= 0 AND ai_tokens >= 0 AND ai_cost_cents >= 0
               AND jobs_queued >= 0 AND jobs_failed >= 0 AND integration_errors >= 0)
);

COMMENT ON TABLE platform_tenant_metric_daily IS
    '按租户按日聚合：只存计数与容量，不含人员、文件、工时、消息等业务正文；'
    '由 Worker 在租户上下文内计算后 upsert，平台域只读';

CREATE INDEX platform_tenant_metric_daily_day_idx ON platform_tenant_metric_daily (day DESC);

CREATE TABLE platform_ai_cost_daily (
    day           date    NOT NULL,
    provider_code text    NOT NULL,
    tokens        bigint  NOT NULL DEFAULT 0,
    cost_cents    bigint  NOT NULL DEFAULT 0,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, provider_code),
    CONSTRAINT platform_ai_cost_daily_non_negative_check CHECK (tokens >= 0 AND cost_cents >= 0)
);

COMMENT ON TABLE platform_ai_cost_daily IS
    'AI 成本日汇总：由模型网关写入，本阶段无网关时保持为空，总览按 0 展示';

-- ------------------------------------------------------------------ 平台目录

CREATE TABLE platform_app_version (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    app_code    text        NOT NULL,
    app_name    text        NOT NULL,
    version     text        NOT NULL,
    status      text        NOT NULL DEFAULT 'published',
    released_at timestamptz NOT NULL DEFAULT now(),
    notes       text,
    CONSTRAINT platform_app_version_key UNIQUE (app_code, version),
    CONSTRAINT platform_app_version_status_check
        CHECK (status IN ('draft', 'published', 'retired'))
);

COMMENT ON TABLE platform_app_version IS '内置应用版本目录：只登记版本与状态，不承载应用数据';

CREATE TABLE platform_connector (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_code text        NOT NULL,
    name           text        NOT NULL,
    auth_type      text        NOT NULL,
    status         text        NOT NULL DEFAULT 'available',
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_connector_code_key UNIQUE (connector_code),
    CONSTRAINT platform_connector_auth_check
        CHECK (auth_type IN ('none', 'api-key', 'oauth2', 'hmac', 'basic')),
    CONSTRAINT platform_connector_status_check CHECK (status IN ('available', 'disabled'))
);

COMMENT ON TABLE platform_connector IS '连接器目录：平台级清单，连接实例与密钥属于租户集成域';

CREATE TABLE platform_plugin_signature (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plugin_code text        NOT NULL,
    version     text        NOT NULL,
    signer      text        NOT NULL,
    key_id      text        NOT NULL,
    sha256      text        NOT NULL,
    status      text        NOT NULL DEFAULT 'trusted',
    signed_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_plugin_signature_key UNIQUE (plugin_code, version),
    CONSTRAINT platform_plugin_signature_sha_check CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT platform_plugin_signature_status_check CHECK (status IN ('trusted', 'revoked'))
);

COMMENT ON TABLE platform_plugin_signature IS
    '受信插件签名：只登记签名指纹与信任状态，插件包本身仍在隔离执行器侧';

CREATE TABLE platform_model_provider (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_code text        NOT NULL,
    name          text        NOT NULL,
    gateway_route text        NOT NULL,
    status        text        NOT NULL DEFAULT 'enabled',
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_model_provider_code_key UNIQUE (provider_code),
    CONSTRAINT platform_model_provider_status_check CHECK (status IN ('enabled', 'disabled'))
);

COMMENT ON TABLE platform_model_provider IS
    '模型供应商目录：只登记网关路由与启停，密钥在 platform_setting 中加密封存';

-- ------------------------------------------------------------------ 平台设置

CREATE TABLE platform_setting (
    setting_key text PRIMARY KEY,
    category    text        NOT NULL,
    value       jsonb       NOT NULL,
    is_secret   boolean     NOT NULL DEFAULT false,
    updated_by  text,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_setting_category_check
        CHECK (category IN ('email', 'storage', 'model', 'limits')),
    CONSTRAINT platform_setting_key_check
        CHECK (setting_key ~ '^(email|storage|model|limits)\.[a-z0-9_.-]{1,64}$')
);

COMMENT ON TABLE platform_setting IS
    '平台设置：键白名单由服务层再次校验；is_secret=true 的值只在写入时接收，读取一律脱敏';

-- ------------------------------------------------------------------ 运行记录

CREATE TABLE platform_operation_run (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind           text        NOT NULL,
    tenant_id      uuid,
    status         text        NOT NULL,
    started_at     timestamptz NOT NULL DEFAULT now(),
    finished_at    timestamptz,
    summary        text        NOT NULL,
    operator_id    uuid REFERENCES platform_operator (id),
    correlation_id text,
    trace_id       text,
    CONSTRAINT platform_operation_run_kind_check
        CHECK (kind IN ('migration', 'backup', 'restore_drill', 'queue_requeue', 'catalog_seed')),
    CONSTRAINT platform_operation_run_status_check
        CHECK (status IN ('running', 'succeeded', 'failed')),
    CONSTRAINT platform_operation_run_summary_check CHECK (length(btrim(summary)) BETWEEN 4 AND 500)
);

COMMENT ON TABLE platform_operation_run IS
    '迁移、备份、恢复演练与队列补偿的平台级运行记录；只存结果摘要，不存数据副本';

CREATE INDEX platform_operation_run_kind_idx ON platform_operation_run (kind, started_at DESC);

-- ------------------------------------------------------------------ 预置数据

INSERT INTO platform_connector (connector_code, name, auth_type, status)
VALUES ('generic-http', '通用 HTTP 连接器', 'api-key', 'available'),
       ('webhook', '出站 Webhook', 'hmac', 'available')
ON CONFLICT (connector_code) DO NOTHING;

INSERT INTO platform_app_version (app_code, app_name, version, status, notes)
VALUES ('platform-api', '企业应用管理平台', '0.1.0', 'published',
        'WP02-07 平台运营端基线')
ON CONFLICT (app_code, version) DO NOTHING;

INSERT INTO platform_setting (setting_key, category, value, is_secret, updated_by)
VALUES ('limits.max_tenants', 'limits', '1000'::jsonb, false, 'migration'),
       ('limits.support_access_max_minutes', 'limits', '60'::jsonb, false, 'migration')
ON CONFLICT (setting_key) DO NOTHING;
