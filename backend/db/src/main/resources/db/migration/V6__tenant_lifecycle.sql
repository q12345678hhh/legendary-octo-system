-- V6 租户生命周期与租户级配置（WP02-01）。
--
-- 设计要点：
-- 1. 状态机只允许 provisioning → active → frozen / closing；frozen → active / closing；
--    closing → active（冷静期内撤回）/ deleted；deleted 为终态。数据库只约束取值，
--    合法迁移由 tenancy 模块的 TenantStatus 与应用服务保证，并在同事务写审计。
-- 2. 生命周期、配置、域名、配额、业务编号、支持访问与申诉全部是租户表，
--    统一 ENABLE + FORCE RLS；平台运营只能通过目标租户上下文进入，不能跨租户扫描。
-- 3. tenancy_plan 是平台级目录（套餐与默认配额/开关），不含任何租户业务数据，故不开 RLS。
-- 4. 业务编号的序列与已发放编号都以 (tenant_id, scope, …) 为键：两个租户可以拥有
--    完全相同的业务编号，互不冲突；同一租户内重复发放才会被主键拒绝。

CREATE TABLE tenancy_plan (
    code       text PRIMARY KEY,
    name       text        NOT NULL,
    quotas     jsonb       NOT NULL,
    features   jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenancy_plan_quotas_check CHECK (jsonb_typeof(quotas) = 'object'),
    CONSTRAINT tenancy_plan_features_check CHECK (jsonb_typeof(features) = 'object')
);

COMMENT ON TABLE tenancy_plan IS
    '平台级套餐目录：只有套餐名与默认配额/功能开关，不含任何租户业务数据，故不启用 RLS';

INSERT INTO tenancy_plan (code, name, quotas, features) VALUES
    ('trial', '试用版',
     '{"users": 10, "storage_mb": 1024, "ai_tokens": 100000, "plugin_installations": 2}'::jsonb,
     '{"ai": false, "plugins": false, "lowcode": false}'::jsonb),
    ('standard', '标准版',
     '{"users": 50, "storage_mb": 10240, "ai_tokens": 1000000, "plugin_installations": 10}'::jsonb,
     '{"ai": false, "plugins": true, "lowcode": false}'::jsonb),
    ('enterprise', '企业版',
     '{"users": 500, "storage_mb": 102400, "ai_tokens": 10000000, "plugin_installations": 100}'::jsonb,
     '{"ai": true, "plugins": true, "lowcode": true}'::jsonb)
ON CONFLICT (code) DO NOTHING;

ALTER TABLE tenancy_tenant
    ADD COLUMN plan_code            text        NOT NULL DEFAULT 'trial',
    ADD COLUMN frozen_at            timestamptz,
    ADD COLUMN frozen_reason        text,
    ADD COLUMN closing_requested_at timestamptz,
    ADD COLUMN cooling_off_until    timestamptz,
    ADD COLUMN deleted_at           timestamptz,
    ADD COLUMN version              bigint      NOT NULL DEFAULT 0;

ALTER TABLE tenancy_tenant
    ADD CONSTRAINT tenancy_tenant_plan_fk FOREIGN KEY (plan_code) REFERENCES tenancy_plan (code);

-- 旧版只有 frozen/closed 两个停用态；closed 迁移为 deleted 终态。
UPDATE tenancy_tenant SET status = 'deleted', deleted_at = now() WHERE status = 'closed';

ALTER TABLE tenancy_tenant DROP CONSTRAINT tenancy_tenant_status_check;
ALTER TABLE tenancy_tenant
    ADD CONSTRAINT tenancy_tenant_status_check
        CHECK (status IN ('provisioning', 'active', 'frozen', 'closing', 'deleted'));

ALTER TABLE tenancy_tenant
    ADD CONSTRAINT tenancy_tenant_closing_fields_check
        CHECK (status <> 'closing'
            OR (closing_requested_at IS NOT NULL AND cooling_off_until IS NOT NULL)),
    ADD CONSTRAINT tenancy_tenant_deleted_fields_check
        CHECK (status <> 'deleted' OR deleted_at IS NOT NULL),
    ADD CONSTRAINT tenancy_tenant_frozen_fields_check
        CHECK (status <> 'frozen' OR (frozen_at IS NOT NULL AND frozen_reason IS NOT NULL));

COMMENT ON COLUMN tenancy_tenant.status IS
    '生命周期：provisioning/active/frozen/closing/deleted；closing 是注销申请后的冷静期，仍可受控导出与申诉';
COMMENT ON COLUMN tenancy_tenant.frozen_reason IS
    '冻结原因必填：冻结会阻断业务写入、插件与 AI 新执行，必须可追溯';
COMMENT ON COLUMN tenancy_tenant.cooling_off_until IS
    '冷静期结束时间；到期前最终删除返回 409，不能在员工仍可申诉时清空租户';

CREATE TABLE tenancy_profile (
    tenant_id   uuid PRIMARY KEY REFERENCES tenancy_tenant (id),
    brand_name  text        NOT NULL,
    brand_color text        NOT NULL DEFAULT '#1f6feb',
    locale      text        NOT NULL DEFAULT 'zh-CN',
    timezone    text        NOT NULL DEFAULT 'Asia/Shanghai',
    features    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  text        NOT NULL,
    CONSTRAINT tenancy_profile_brand_name_check
        CHECK (length(btrim(brand_name)) BETWEEN 1 AND 100),
    CONSTRAINT tenancy_profile_brand_color_check
        CHECK (brand_color ~ '^#[0-9a-fA-F]{6}$'),
    CONSTRAINT tenancy_profile_locale_check
        CHECK (locale IN ('zh-CN', 'en-US')),
    CONSTRAINT tenancy_profile_timezone_check
        CHECK (length(btrim(timezone)) BETWEEN 1 AND 64),
    CONSTRAINT tenancy_profile_features_check
        CHECK (jsonb_typeof(features) = 'object')
);

COMMENT ON TABLE tenancy_profile IS
    '租户品牌与区域配置：语言、时区、功能开关；功能开关只能由平台在套餐范围内调整';

ALTER TABLE tenancy_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_profile FORCE ROW LEVEL SECURITY;

CREATE POLICY tenancy_profile_isolation ON tenancy_profile
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE tenancy_domain (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenancy_tenant (id),
    domain     text        NOT NULL,
    kind       text        NOT NULL DEFAULT 'primary',
    verified   boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenancy_domain_domain_unique UNIQUE (domain),
    CONSTRAINT tenancy_domain_kind_check CHECK (kind IN ('primary', 'alias')),
    CONSTRAINT tenancy_domain_format_check
        CHECK (domain ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$')
);

COMMENT ON TABLE tenancy_domain IS
    '租户域名：domain 全局唯一（登录前按域名路由租户），每个租户最多一个 primary';
COMMENT ON COLUMN tenancy_domain.domain IS
    '全小写 FQDN；唯一约束是跨租户的，冲突时不得创建，避免同名域名指向两个租户';

CREATE UNIQUE INDEX tenancy_domain_primary_unique
    ON tenancy_domain (tenant_id) WHERE kind = 'primary';
CREATE INDEX tenancy_domain_tenant_idx ON tenancy_domain (tenant_id, created_at DESC);

ALTER TABLE tenancy_domain ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_domain FORCE ROW LEVEL SECURITY;

CREATE POLICY tenancy_domain_isolation ON tenancy_domain
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE tenancy_quota (
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    quota_key   text        NOT NULL,
    limit_value bigint      NOT NULL,
    used_value  bigint      NOT NULL DEFAULT 0,
    unit        text        NOT NULL DEFAULT 'count',
    period      text        NOT NULL DEFAULT 'month',
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenancy_quota_pk PRIMARY KEY (tenant_id, quota_key),
    CONSTRAINT tenancy_quota_key_check CHECK (quota_key ~ '^[a-z][a-z0-9_]{1,63}$'),
    CONSTRAINT tenancy_quota_limit_check CHECK (limit_value >= 0),
    CONSTRAINT tenancy_quota_used_check CHECK (used_value >= 0),
    CONSTRAINT tenancy_quota_period_check CHECK (period IN ('day', 'month', 'total'))
);

COMMENT ON TABLE tenancy_quota IS
    '租户配额与使用量：used_value 只增不减；拒绝超额由服务层用带条件的 UPDATE 保证';

CREATE INDEX tenancy_quota_tenant_idx ON tenancy_quota (tenant_id, quota_key);

ALTER TABLE tenancy_quota ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_quota FORCE ROW LEVEL SECURITY;

CREATE POLICY tenancy_quota_isolation ON tenancy_quota
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE tenancy_business_number_sequence (
    tenant_id  uuid   NOT NULL REFERENCES tenancy_tenant (id),
    scope      text   NOT NULL,
    next_value bigint NOT NULL DEFAULT 1,
    CONSTRAINT tenancy_business_number_sequence_pk PRIMARY KEY (tenant_id, scope),
    CONSTRAINT tenancy_business_number_scope_check CHECK (scope ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT tenancy_business_number_next_check CHECK (next_value >= 1)
);

COMMENT ON TABLE tenancy_business_number_sequence IS
    '按租户与业务域独立发号；并发安全靠 INSERT ... ON CONFLICT ... RETURNING 单语句完成';

ALTER TABLE tenancy_business_number_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_business_number_sequence FORCE ROW LEVEL SECURITY;

CREATE POLICY tenancy_business_number_sequence_isolation ON tenancy_business_number_sequence
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE tenancy_business_number (
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    scope           text        NOT NULL,
    business_number text        NOT NULL,
    sequence        bigint      NOT NULL,
    issued_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenancy_business_number_pk PRIMARY KEY (tenant_id, scope, business_number),
    CONSTRAINT tenancy_business_number_seq_unique UNIQUE (tenant_id, scope, sequence),
    CONSTRAINT tenancy_business_number_format_check CHECK (length(btrim(business_number)) > 0)
);

COMMENT ON TABLE tenancy_business_number IS
    '已发放业务编号：主键以 tenant_id 开头，两个租户同号并存是预期行为，不是冲突';

CREATE INDEX tenancy_business_number_tenant_idx
    ON tenancy_business_number (tenant_id, issued_at DESC);

ALTER TABLE tenancy_business_number ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_business_number FORCE ROW LEVEL SECURITY;

CREATE POLICY tenancy_business_number_isolation ON tenancy_business_number
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE tenancy_lifecycle_audit (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenancy_tenant (id),
    action         text        NOT NULL,
    from_status    text,
    to_status      text        NOT NULL,
    actor          text        NOT NULL,
    reason         text,
    ticket_id      text,
    correlation_id text        NOT NULL,
    trace_id       text,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenancy_lifecycle_audit_action_check
        CHECK (action IN ('created', 'initialized', 'frozen', 'restored',
                          'closing_requested', 'closing_cancelled', 'deleted',
                          'exported', 'appeal_submitted',
                          'support_access_requested', 'support_access_approved',
                          'support_access_denied', 'support_access_revoked')),
    CONSTRAINT tenancy_lifecycle_audit_actor_check CHECK (length(btrim(actor)) > 0)
);

COMMENT ON TABLE tenancy_lifecycle_audit IS
    '租户生命周期审计：状态变更、受控导出、申诉与支持访问都必须留痕，禁止业务模块改写';

CREATE INDEX tenancy_lifecycle_audit_tenant_idx
    ON tenancy_lifecycle_audit (tenant_id, occurred_at DESC);

ALTER TABLE tenancy_lifecycle_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_lifecycle_audit FORCE ROW LEVEL SECURITY;

CREATE POLICY tenancy_lifecycle_audit_isolation ON tenancy_lifecycle_audit
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE tenancy_support_access (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenancy_tenant (id),
    ticket_id         text        NOT NULL,
    operator_id       text        NOT NULL,
    reason            text        NOT NULL,
    requested_minutes integer     NOT NULL,
    scope             text        NOT NULL DEFAULT 'tenant_admin_read',
    status            text        NOT NULL DEFAULT 'pending',
    requested_at      timestamptz NOT NULL DEFAULT now(),
    approved_by       text,
    approved_at       timestamptz,
    expires_at        timestamptz,
    revoked_at        timestamptz,
    last_used_at      timestamptz,
    CONSTRAINT tenancy_support_access_status_check
        CHECK (status IN ('pending', 'approved', 'denied', 'expired', 'revoked')),
    CONSTRAINT tenancy_support_access_ticket_check
        CHECK (ticket_id ~ '^[A-Z][A-Z0-9-]{3,31}$'),
    CONSTRAINT tenancy_support_access_reason_check
        CHECK (length(btrim(reason)) BETWEEN 10 AND 500),
    CONSTRAINT tenancy_support_access_minutes_check
        CHECK (requested_minutes BETWEEN 1 AND 60),
    CONSTRAINT tenancy_support_access_scope_check
        CHECK (scope IN ('tenant_admin_read')),
    CONSTRAINT tenancy_support_access_approved_fields_check
        CHECK (status <> 'approved'
            OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND expires_at IS NOT NULL)),
    CONSTRAINT tenancy_support_access_separation_check
        CHECK (approved_by IS NULL OR approved_by <> operator_id)
);

COMMENT ON TABLE tenancy_support_access IS
    '平台支持访问：必须有工单、理由、独立审批人与最长 60 分钟时限；只读，每次使用留痕';
COMMENT ON COLUMN tenancy_support_access.approved_by IS
    '审批人不得等于申请人：这是职责分离约束，由数据库 CHECK 与服务层双重保证';

CREATE INDEX tenancy_support_access_tenant_idx
    ON tenancy_support_access (tenant_id, requested_at DESC);
CREATE INDEX tenancy_support_access_active_idx
    ON tenancy_support_access (tenant_id, status, expires_at)
    WHERE status = 'approved';

ALTER TABLE tenancy_support_access ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_support_access FORCE ROW LEVEL SECURITY;

CREATE POLICY tenancy_support_access_isolation ON tenancy_support_access
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE tenancy_appeal (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    subject_id    text        NOT NULL,
    reason        text        NOT NULL,
    status        text        NOT NULL DEFAULT 'submitted',
    submitted_at  timestamptz NOT NULL DEFAULT now(),
    resolved_at   timestamptz,
    resolved_by   text,
    resolution    text,
    CONSTRAINT tenancy_appeal_status_check
        CHECK (status IN ('submitted', 'accepted', 'rejected')),
    CONSTRAINT tenancy_appeal_reason_check
        CHECK (length(btrim(reason)) BETWEEN 10 AND 2000),
    CONSTRAINT tenancy_appeal_subject_check
        CHECK (length(btrim(subject_id)) > 0)
);

COMMENT ON TABLE tenancy_appeal IS
    '冻结或注销冷静期内的申诉：冻结后仍保留的唯一员工侧写入口之一';

CREATE INDEX tenancy_appeal_tenant_idx ON tenancy_appeal (tenant_id, submitted_at DESC);

ALTER TABLE tenancy_appeal ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_appeal FORCE ROW LEVEL SECURITY;

CREATE POLICY tenancy_appeal_isolation ON tenancy_appeal
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
