-- V18 模块目录与模块建议。
--
-- 设计要点：
--   1. 模块目录（app_module）只描述「有哪些模块、谁负责、版本/依赖/适用范围/健康/安装占位」，
--      不承载低代码配置正文；安装状态仅作占位，本阶段不执行真实安装。
--   2. 建议（app_module_suggestion）覆盖草稿、沟通、受理、配置、测试、整改、最终批准、
--      驳回、撤回、启用、特殊启用待复核、暂停、关闭与弃用；状态流转由应用服务校验。
--   3. 建议事件（app_module_suggestion_event）只追加：同时作为审计与站内通知，
--      触发器禁止 UPDATE/DELETE；避免跨模块直接写 collaboration 的通知表。
--   4. 所有表启用并强制 RLS；每表恰好一条租户隔离策略。

CREATE TABLE app_module (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenancy_tenant (id),
    code              text        NOT NULL,
    name              text        NOT NULL,
    category          text        NOT NULL DEFAULT '通用',
    description       text        NOT NULL DEFAULT '',
    template_key      text,
    owner_person_id   uuid,
    version           text        NOT NULL DEFAULT '1.0.0',
    dependencies      jsonb       NOT NULL DEFAULT '[]'::jsonb,
    applicable_scope  text        NOT NULL DEFAULT 'tenant',
    health_status     text        NOT NULL DEFAULT 'unknown',
    install_status    text        NOT NULL DEFAULT 'not_installed',
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_module_code_check CHECK (code ~ '^[a-z][a-z0-9._-]{2,63}$'),
    CONSTRAINT app_module_name_check CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT app_module_scope_check
        CHECK (applicable_scope IN ('tenant', 'department', 'project', 'person')),
    CONSTRAINT app_module_health_check
        CHECK (health_status IN ('healthy', 'degraded', 'offline', 'unknown')),
    CONSTRAINT app_module_install_check
        CHECK (install_status IN ('not_installed', 'installed', 'disabled')),
    CONSTRAINT app_module_dependencies_check CHECK (jsonb_typeof(dependencies) = 'array'),
    CONSTRAINT app_module_unique UNIQUE (tenant_id, code)
);

COMMENT ON TABLE app_module IS
    '模块目录：负责人、版本、依赖、适用范围、健康与安装占位；本阶段不做真实安装';

CREATE INDEX app_module_list_idx ON app_module (tenant_id, category, name);

ALTER TABLE app_module ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_module FORCE ROW LEVEL SECURITY;

CREATE POLICY app_module_isolation ON app_module
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE app_module_suggestion (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                uuid        NOT NULL REFERENCES tenancy_tenant (id),
    title                    text        NOT NULL,
    requester_person_id      uuid        NOT NULL,
    business_problem         text        NOT NULL,
    goal                     text        NOT NULL DEFAULT '',
    scope                    text        NOT NULL DEFAULT '',
    data_scope               text        NOT NULL DEFAULT '',
    permissions_needed       text        NOT NULL DEFAULT '',
    cost_estimate            text        NOT NULL DEFAULT '',
    benefit_estimate         text        NOT NULL DEFAULT '',
    suggested_owner_person_id uuid,
    config_owner_person_id   uuid,
    status                   text        NOT NULL DEFAULT 'draft',
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_module_suggestion_title_check
        CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT app_module_suggestion_problem_check
        CHECK (length(btrim(business_problem)) BETWEEN 1 AND 4000),
    CONSTRAINT app_module_suggestion_status_check CHECK (status IN (
        'draft', 'communicating', 'submitted', 'configuring', 'testing',
        'rectifying', 'test_failed', 'pending_final_approval', 'rejected',
        'withdrawn', 'enabled', 'special_pending_review', 'paused', 'closed',
        'deprecated'))
);

COMMENT ON TABLE app_module_suggestion IS
    '模块建议：全员可发起；上级沟通、老板受理/驳回/要求补充，受理后指定配置负责人';

CREATE INDEX app_module_suggestion_requester_idx
    ON app_module_suggestion (tenant_id, requester_person_id, created_at DESC);
CREATE INDEX app_module_suggestion_status_idx
    ON app_module_suggestion (tenant_id, status, updated_at DESC);

ALTER TABLE app_module_suggestion ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_module_suggestion FORCE ROW LEVEL SECURITY;

CREATE POLICY app_module_suggestion_isolation ON app_module_suggestion
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE app_module_suggestion_event (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenancy_tenant (id),
    suggestion_id      uuid        NOT NULL REFERENCES app_module_suggestion (id),
    event_kind         text        NOT NULL DEFAULT 'transition',
    from_status        text,
    to_status          text,
    action             text        NOT NULL,
    actor_person_id    uuid,
    recipient_person_id uuid,
    comment            text        NOT NULL DEFAULT '',
    occurred_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_module_suggestion_event_kind_check
        CHECK (event_kind IN ('transition', 'comment', 'notification')),
    CONSTRAINT app_module_suggestion_event_action_check
        CHECK (length(btrim(action)) > 0)
);

COMMENT ON TABLE app_module_suggestion_event IS
    '建议状态流转/沟通/通知事件：只追加，同时作为审计与站内通知';

CREATE INDEX app_module_suggestion_event_idx
    ON app_module_suggestion_event (tenant_id, suggestion_id, occurred_at);

ALTER TABLE app_module_suggestion_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_module_suggestion_event FORCE ROW LEVEL SECURITY;

CREATE POLICY app_module_suggestion_event_isolation ON app_module_suggestion_event
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION app_module_suggestion_event_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'app_module_suggestion_event 只允许插入，不允许修改或删除';
END $$;

CREATE TRIGGER app_module_suggestion_event_immutable_trigger
    BEFORE UPDATE OR DELETE ON app_module_suggestion_event
    FOR EACH ROW EXECUTE FUNCTION app_module_suggestion_event_immutable();
