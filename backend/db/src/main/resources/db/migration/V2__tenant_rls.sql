-- 租户行级安全（RLS）策略的首个实例，规则来源见 docs/adr/ADR-0002-rls-tenant-context.md。
--
-- 四条硬约束，缺一条隔离就会被削弱：
-- 1. ENABLE 与 FORCE 必须同时出现。只有 ENABLE 时表所有者仍可绕过策略；
--    FORCE 让所有者同样受策略约束，只剩超级用户能绕（platform_owner 只用于迁移，不接运行期流量）。
-- 2. 租户上下文只允许事务级 SET LOCAL app.tenant_id = '<uuid>'，提交/回滚后自动失效；
--    会话级 SET 会在连接池复用时把租户串到下一个请求（尖峰 A CASE 6 已实测）。
-- 3. 读取上下文必须带 nullif(..., '')：RESET 后 current_setting 返回空串，
--    裸转型抛 invalid input syntax 而不是安全返回空集（尖峰 A CASE 5）。
-- 4. 无上下文返回 0 行是预期行为，不是故障；后台任务必须自己建立上下文。

ALTER TABLE tenancy_tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_tenant FORCE ROW LEVEL SECURITY;

COMMENT ON TABLE tenancy_tenant IS
    '租户主表：id 即 tenant_id，其余业务表以它作为隔离键；已启用 FORCE RLS，无上下文返回 0 行';

CREATE POLICY tenancy_tenant_isolation ON tenancy_tenant
    FOR ALL
    USING (id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (id = nullif(current_setting('app.tenant_id', true), '')::uuid);

COMMENT ON POLICY tenancy_tenant_isolation ON tenancy_tenant IS
    '只能用事务级 SET LOCAL 设置 app.tenant_id；跨租户读写一律拒绝，无上下文视为空集';
