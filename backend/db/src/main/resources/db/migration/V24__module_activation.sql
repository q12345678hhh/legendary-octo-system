-- V24 模块启用状态与运行时装配。
--
-- 设计要点：
--   1. 启用粒度是租户级：每个租户对每个模块最多一条激活记录。
--   2. 记录缺失视为启用（保证存量租户与开发库不被误拦截）；只有显式 disabled 才拦截。
--   3. install_status 仍表示「是否可启用」，与激活状态解耦；未安装不允许启用。
--   4. 激活事件只追加，作为审计：触发器禁止 UPDATE/DELETE。
--   5. 迁移时为既有租户的全部模块回填 enabled。

CREATE TABLE app_module_activation (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenancy_tenant (id),
    module_id    uuid        NOT NULL REFERENCES app_module (id) ON DELETE CASCADE,
    status       text        NOT NULL DEFAULT 'enabled',
    config       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    enabled_at   timestamptz,
    enabled_by   text,
    disabled_at  timestamptz,
    disabled_by  text,
    version      bigint      NOT NULL DEFAULT 1,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_module_activation_status_check
        CHECK (status IN ('enabled', 'disabled')),
    CONSTRAINT app_module_activation_config_check
        CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT app_module_activation_unique UNIQUE (tenant_id, module_id)
);

COMMENT ON TABLE app_module_activation IS
    '模块启用状态：租户级；记录缺失视为启用，显式 disabled 才在运行时拦截';

CREATE INDEX app_module_activation_status_idx
    ON app_module_activation (tenant_id, status);

CREATE TABLE app_module_activation_event (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenancy_tenant (id),
    module_id    uuid        NOT NULL REFERENCES app_module (id) ON DELETE CASCADE,
    action       text        NOT NULL,
    from_status  text,
    to_status    text        NOT NULL,
    actor        text        NOT NULL DEFAULT '',
    comment      text        NOT NULL DEFAULT '',
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_module_activation_event_action_check
        CHECK (length(btrim(action)) > 0),
    CONSTRAINT app_module_activation_event_status_check
        CHECK (to_status IN ('enabled', 'disabled')),
    CONSTRAINT app_module_activation_event_from_check
        CHECK (from_status IS NULL OR from_status IN ('enabled', 'disabled'))
);

COMMENT ON TABLE app_module_activation_event IS
    '模块启用/停用事件：只追加，作为启停审计';

CREATE INDEX app_module_activation_event_idx
    ON app_module_activation_event (tenant_id, module_id, occurred_at);

-- 回填在开启 RLS 之前完成：迁移账号是表 owner，FORCE RLS 会把它也挡在策略外。
-- 同时暂时关闭 app_module 的 FORCE，确保跨租户读取可见。
ALTER TABLE app_module NO FORCE ROW LEVEL SECURITY;

INSERT INTO app_module_activation (tenant_id, module_id, status, enabled_at, enabled_by)
SELECT m.tenant_id, m.id, 'enabled', now(), 'migration:v24'
  FROM app_module m
 WHERE NOT EXISTS (
       SELECT 1
         FROM app_module_activation a
        WHERE a.tenant_id = m.tenant_id
          AND a.module_id = m.id);

ALTER TABLE app_module FORCE ROW LEVEL SECURITY;

ALTER TABLE app_module_activation ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_module_activation FORCE ROW LEVEL SECURITY;

CREATE POLICY app_module_activation_isolation ON app_module_activation
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE app_module_activation_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_module_activation_event FORCE ROW LEVEL SECURITY;

CREATE POLICY app_module_activation_event_isolation ON app_module_activation_event
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION app_module_activation_event_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'app_module_activation_event 只允许插入，不允许修改或删除';
END $$;

CREATE TRIGGER app_module_activation_event_immutable_trigger
    BEFORE UPDATE OR DELETE ON app_module_activation_event
    FOR EACH ROW EXECUTE FUNCTION app_module_activation_event_immutable();
