-- 租户表建表模板（复制后替换 <module>_<entity>，不要直接作为迁移执行）。
--
-- 为什么要有模板：RLS 的三件套（ENABLE、FORCE、nullif 策略）每漏一件，
-- 隔离就从「数据库强制」退化为「应用自觉」。新表照抄本模板，评审时逐行核对，
-- 具体理由见 docs/adr/ADR-0002-rls-tenant-context.md 与 docs/runbooks/db-migration.md。

CREATE TABLE <module>_<entity> (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenancy_tenant (id),
    -- 业务列在此展开
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 每个跨租户扫描都必须以 tenant_id 开头，否则策略谓词用不上索引
CREATE INDEX <module>_<entity>_tenant_idx ON <module>_<entity> (tenant_id, created_at DESC);

ALTER TABLE <module>_<entity> ENABLE ROW LEVEL SECURITY;
ALTER TABLE <module>_<entity> FORCE ROW LEVEL SECURITY;

CREATE POLICY <module>_<entity>_isolation ON <module>_<entity>
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

COMMENT ON TABLE <module>_<entity> IS
    '租户业务表：已启用 FORCE RLS，无上下文返回 0 行；跨模块只能经公开应用服务访问';

-- 自检：迁移后应能在 pg_class 看到 relrowsecurity 与 relforcerowsecurity 同时为真。
-- SELECT relname, relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = '<module>_<entity>';
