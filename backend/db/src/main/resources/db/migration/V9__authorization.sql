-- V9 统一授权决策：权限目录、自定义角色、授权、显式拒绝、字段与条件策略、
-- 缓存版本与决策审计。系统角色模板在代码中（SystemRoleCatalog），不入库。
--
-- 边界：
-- 1. 所有租户表照抄 backend/db/templates/tenant-table.template.sql：
--    ENABLE + FORCE RLS，策略统一 tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid；
-- 2. 权限目录是产品级全局表，租户只读；租户不可增删权限码；
-- 3. 授权、拒绝、字段、条件、成员、组织关系任一变化，触发器在同一事务内 bump 租户缓存版本，
--    应用侧按版本失效，不依赖各模块手工调用；
-- 4. 临时授权靠 valid_to 到期即失效；委托用 delegated_from_id 指向源授权，范围不得放大。

-- ------------------------------------------------------------------ 全局权限目录

CREATE TABLE authorization_permission (
    code          text PRIMARY KEY,
    resource_type text NOT NULL,
    action        text NOT NULL,
    description   text NOT NULL,
    sensitive     boolean NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_permission_code_check
        CHECK (code ~ '^[a-z][a-z0-9_.-]{2,127}$'),
    CONSTRAINT authorization_permission_resource_check
        CHECK (length(btrim(resource_type)) BETWEEN 1 AND 64),
    CONSTRAINT authorization_permission_action_check
        CHECK (length(btrim(action)) BETWEEN 1 AND 64)
);

COMMENT ON TABLE authorization_permission IS
    '产品级权限目录：租户只读，授权只能引用这里已存在的权限码';

INSERT INTO authorization_permission (code, resource_type, action, description, sensitive) VALUES
    ('directory.person.read', 'directory.person', 'read', '查看人员目录', false),
    ('directory.employment.read', 'directory.employment', 'read', '查看雇佣关系', true),
    ('directory.person.manage', 'directory.person', 'manage', '维护自然人与雇佣关系', true),
    ('directory.person.export', 'directory.person', 'export', '导出人员目录', true),
    ('organization.structure.read', 'organization.structure', 'read', '查看组织结构', false),
    ('organization.structure.manage', 'organization.structure', 'manage', '维护组织结构', true),
    ('file.read', 'file', 'read', '读取文件', true),
    ('file.manage', 'file', 'manage', '管理文件与扫描结果', true),
    ('search.read', 'search', 'read', '使用全局搜索', false),
    ('report.read', 'report', 'read', '查看报表', false),
    ('report.export', 'report', 'export', '导出报表', true),
    ('ai.context.read', 'ai.context', 'read', '允许数据进入 AI 上下文', true),
    ('authorization.role.manage', 'authorization.role', 'manage', '维护自定义角色与权限集合', true),
    ('authorization.assignment.manage', 'authorization.assignment', 'manage', '授予、撤销与委托', true),
    ('authorization.deny.manage', 'authorization.deny', 'manage', '维护显式拒绝', true),
    ('authorization.policy.manage', 'authorization.policy', 'manage', '维护字段与条件策略', true),
    ('authorization.decision.check', 'authorization.decision', 'check', '为他人执行权限判定', true),
    ('authorization.decision.explain', 'authorization.decision', 'explain', '解释与模拟权限判定', true),
    ('authorization.audit.read', 'authorization.audit', 'read', '查看授权决策审计', true),
    ('authorization.cache.read', 'authorization.cache', 'read', '查看权限缓存版本', false),
    ('tenant.admin', 'tenant', 'admin', '租户管理动作', true),
    ('identity.member.manage', 'identity.member', 'manage', '管理租户成员', true),
    ('business.work.read', 'business.work', 'read', '查看业务工作数据', true);

-- 授权记录用 (tenant_id, actor_id) 复合外键指向统一主体；id 是主键，这里补唯一约束
-- 只用于让「同租户内引用」在数据库层成立。
ALTER TABLE identity_actor
    ADD CONSTRAINT identity_actor_tenant_id_unique UNIQUE (tenant_id, id);

-- ------------------------------------------------------------------ 自定义角色

CREATE TABLE authorization_role (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenancy_tenant (id),
    code        text NOT NULL,
    name        text NOT NULL,
    description text,
    status      text NOT NULL DEFAULT 'active',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_role_status_check CHECK (status IN ('active', 'inactive')),
    CONSTRAINT authorization_role_code_check CHECK (code ~ '^[a-z][a-z0-9-]{2,63}$'),
    CONSTRAINT authorization_role_name_check CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT authorization_role_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT authorization_role_tenant_id_unique UNIQUE (tenant_id, id)
);

COMMENT ON TABLE authorization_role IS
    '租户自定义角色：系统角色模板在代码中，租户可复制权限集合后收窄，不能直接修改系统模板';

CREATE INDEX authorization_role_status_idx ON authorization_role (tenant_id, status);

ALTER TABLE authorization_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_role FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_role_isolation ON authorization_role
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE authorization_role_permission (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenancy_tenant (id),
    role_id         uuid NOT NULL,
    permission_code text NOT NULL REFERENCES authorization_permission (code),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_role_permission_unique UNIQUE (tenant_id, role_id, permission_code),
    CONSTRAINT authorization_role_permission_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT authorization_role_permission_role_fk
        FOREIGN KEY (tenant_id, role_id) REFERENCES authorization_role (tenant_id, id)
        ON DELETE CASCADE
);

COMMENT ON TABLE authorization_role_permission IS
    '自定义角色的权限集合；系统角色不进本表，由 SystemRoleCatalog 在代码中给出';

CREATE INDEX authorization_role_permission_role_idx
    ON authorization_role_permission (tenant_id, role_id);

ALTER TABLE authorization_role_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_role_permission FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_role_permission_isolation ON authorization_role_permission
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 授权与拒绝

CREATE TABLE authorization_assignment (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenancy_tenant (id),
    actor_id          uuid NOT NULL,
    member_id         uuid,
    role_code         text NOT NULL,
    scope_type        text NOT NULL,
    scope_id          uuid,
    valid_from        timestamptz NOT NULL DEFAULT now(),
    valid_to          timestamptz,
    reason            text NOT NULL,
    granted_by        text NOT NULL,
    delegated_from_id uuid,
    revoked_at        timestamptz,
    revoked_by        text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_assignment_role_check
        CHECK (role_code ~ '^[a-z][a-z0-9-]{2,63}$'),
    CONSTRAINT authorization_assignment_reason_check CHECK (length(btrim(reason)) > 0),
    CONSTRAINT authorization_assignment_scope_check CHECK (
        (scope_type IN ('tenant', 'self', 'self_created', 'direct_reports', 'reporting_chain')
            AND scope_id IS NULL)
        OR (scope_type IN ('org_unit', 'org_unit_subtree', 'delegated', 'project_space',
                           'legal_entity')
            AND scope_id IS NOT NULL)
    ),
    CONSTRAINT authorization_assignment_period_check
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT authorization_assignment_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT authorization_assignment_actor_fk
        FOREIGN KEY (tenant_id, actor_id) REFERENCES identity_actor (tenant_id, id),
    CONSTRAINT authorization_assignment_delegated_fk
        FOREIGN KEY (tenant_id, delegated_from_id) REFERENCES authorization_assignment (tenant_id, id)
);

COMMENT ON TABLE authorization_assignment IS
    '授权记录：valid_to 为空的长期授权须有业务依据；临时授权必须有到期时间，委托必须指向源授权';
COMMENT ON COLUMN authorization_assignment.delegated_from_id IS
    '委托来源：同租户、同角色、范围不得大于源授权、到期不得晚于源授权';

CREATE INDEX authorization_assignment_actor_idx
    ON authorization_assignment (tenant_id, actor_id, revoked_at, valid_to);
CREATE INDEX authorization_assignment_role_idx
    ON authorization_assignment (tenant_id, role_code, revoked_at);

ALTER TABLE authorization_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_assignment FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_assignment_isolation ON authorization_assignment
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE authorization_deny (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenancy_tenant (id),
    actor_id        uuid,
    role_code       text,
    permission_code text NOT NULL REFERENCES authorization_permission (code),
    resource_type   text,
    resource_id     uuid,
    scope_type      text,
    scope_id        uuid,
    reason          text NOT NULL,
    expires_at      timestamptz,
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_deny_subject_check CHECK (actor_id IS NOT NULL OR role_code IS NOT NULL),
    CONSTRAINT authorization_deny_reason_check CHECK (length(btrim(reason)) > 0),
    CONSTRAINT authorization_deny_scope_check CHECK (
        scope_type IS NULL
        OR (scope_type IN ('tenant', 'self', 'self_created', 'direct_reports', 'reporting_chain')
            AND scope_id IS NULL)
        OR (scope_type IN ('org_unit', 'org_unit_subtree', 'delegated', 'project_space',
                           'legal_entity')
            AND scope_id IS NOT NULL)
    )
);

COMMENT ON TABLE authorization_deny IS
    '显式拒绝：优先于任何允许；可按主体、角色、资源与范围收窄，可设置到期时间';

CREATE INDEX authorization_deny_actor_idx
    ON authorization_deny (tenant_id, actor_id, permission_code, expires_at);
CREATE INDEX authorization_deny_role_idx
    ON authorization_deny (tenant_id, role_code, permission_code, expires_at);

-- 未指定资源与范围的长期拒绝只允许存在一条，避免重复堆积
CREATE UNIQUE INDEX authorization_deny_actor_global_unique
    ON authorization_deny (tenant_id, actor_id, permission_code)
    WHERE actor_id IS NOT NULL AND resource_type IS NULL AND resource_id IS NULL
      AND scope_type IS NULL AND scope_id IS NULL AND expires_at IS NULL;
CREATE UNIQUE INDEX authorization_deny_role_global_unique
    ON authorization_deny (tenant_id, role_code, permission_code)
    WHERE role_code IS NOT NULL AND resource_type IS NULL AND resource_id IS NULL
      AND scope_type IS NULL AND scope_id IS NULL AND expires_at IS NULL;

ALTER TABLE authorization_deny ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_deny FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_deny_isolation ON authorization_deny
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 字段与条件策略

CREATE TABLE authorization_field_policy (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenancy_tenant (id),
    role_code     text NOT NULL,
    resource_type text NOT NULL,
    field_name    text NOT NULL,
    can_view      boolean NOT NULL DEFAULT false,
    can_edit      boolean NOT NULL DEFAULT false,
    can_export    boolean NOT NULL DEFAULT false,
    can_use_ai    boolean NOT NULL DEFAULT false,
    effect        text NOT NULL DEFAULT 'allow',
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_field_policy_effect_check CHECK (effect IN ('allow', 'deny')),
    CONSTRAINT authorization_field_policy_unique
        UNIQUE (tenant_id, role_code, resource_type, field_name),
    CONSTRAINT authorization_field_policy_role_check
        CHECK (role_code ~ '^[a-z][a-z0-9-]{2,63}$')
);

COMMENT ON TABLE authorization_field_policy IS
    '字段级策略：deny 优先于记录级允许；视图、编辑、导出、AI 四类用途分别授权';

CREATE INDEX authorization_field_policy_resource_idx
    ON authorization_field_policy (tenant_id, resource_type, role_code);

ALTER TABLE authorization_field_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_field_policy FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_field_policy_isolation ON authorization_field_policy
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE authorization_condition_policy (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenancy_tenant (id),
    permission_code text NOT NULL REFERENCES authorization_permission (code),
    name            text NOT NULL,
    condition       jsonb NOT NULL DEFAULT '{}'::jsonb,
    effect          text NOT NULL DEFAULT 'allow',
    priority        integer NOT NULL DEFAULT 100,
    status          text NOT NULL DEFAULT 'active',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_condition_policy_effect_check CHECK (effect IN ('allow', 'deny')),
    CONSTRAINT authorization_condition_policy_status_check CHECK (status IN ('active', 'inactive')),
    CONSTRAINT authorization_condition_policy_json_check CHECK (jsonb_typeof(condition) = 'object'),
    CONSTRAINT authorization_condition_policy_name_check CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT authorization_condition_policy_priority_check CHECK (priority >= 0)
);

COMMENT ON TABLE authorization_condition_policy IS
    '条件策略：只支持白名单条件类型；缺少所需属性按不通过处理（fail-closed）';

CREATE INDEX authorization_condition_policy_permission_idx
    ON authorization_condition_policy (tenant_id, permission_code, status, priority);

ALTER TABLE authorization_condition_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_condition_policy FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_condition_policy_isolation ON authorization_condition_policy
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 缓存版本与决策审计

CREATE TABLE authorization_cache_version (
    tenant_id  uuid PRIMARY KEY REFERENCES tenancy_tenant (id),
    version    bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_cache_version_version_check CHECK (version >= 1)
);

COMMENT ON TABLE authorization_cache_version IS
    '租户权限缓存版本：授权、拒绝、字段、条件、成员与组织关系变化时由触发器递增';

ALTER TABLE authorization_cache_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_cache_version FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_cache_version_isolation ON authorization_cache_version
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE authorization_decision_log (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenancy_tenant (id),
    actor_id        uuid NOT NULL,
    permission_code text NOT NULL,
    resource_type   text,
    resource_id     uuid,
    scope_type      text,
    scope_id        uuid,
    allowed         boolean NOT NULL,
    reason_code     text NOT NULL,
    factors         jsonb NOT NULL DEFAULT '[]'::jsonb,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_decision_log_factors_check CHECK (jsonb_typeof(factors) = 'array'),
    CONSTRAINT authorization_decision_log_tenant_id_unique UNIQUE (tenant_id, id)
);

COMMENT ON TABLE authorization_decision_log IS
    '授权决策审计：只插入，触发器阻止修改与删除；只记录拒绝与解释请求，避免写放大';

CREATE INDEX authorization_decision_log_tenant_idx
    ON authorization_decision_log (tenant_id, occurred_at DESC);
CREATE INDEX authorization_decision_log_actor_idx
    ON authorization_decision_log (tenant_id, actor_id, occurred_at DESC);

CREATE FUNCTION authorization_decision_log_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'authorization_decision_log 只允许插入，不允许修改或删除';
END
$$;

CREATE TRIGGER authorization_decision_log_immutable_trigger
    BEFORE UPDATE OR DELETE ON authorization_decision_log
    FOR EACH ROW EXECUTE FUNCTION authorization_decision_log_immutable();

ALTER TABLE authorization_decision_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_decision_log FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_decision_log_isolation ON authorization_decision_log
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 版本触发

CREATE FUNCTION authorization_bump_version() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    target_tenant uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_tenant := OLD.tenant_id;
    ELSE
        target_tenant := NEW.tenant_id;
    END IF;

    INSERT INTO authorization_cache_version (tenant_id)
    VALUES (target_tenant)
    ON CONFLICT (tenant_id) DO UPDATE
       SET version = authorization_cache_version.version + 1,
           updated_at = now();

    RETURN NULL;
END
$$;

COMMENT ON FUNCTION authorization_bump_version() IS
    '任一授权相关表变化时递增租户缓存版本，保证 60 秒内失效且同事务可见';

CREATE TRIGGER authorization_role_permission_bump
    AFTER INSERT OR UPDATE OR DELETE ON authorization_role_permission
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER authorization_assignment_bump
    AFTER INSERT OR UPDATE OR DELETE ON authorization_assignment
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER authorization_deny_bump
    AFTER INSERT OR UPDATE OR DELETE ON authorization_deny
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER authorization_field_policy_bump
    AFTER INSERT OR UPDATE OR DELETE ON authorization_field_policy
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER authorization_condition_policy_bump
    AFTER INSERT OR UPDATE OR DELETE ON authorization_condition_policy
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

-- 成员、雇佣、岗位、部门树、汇报线、委派与老板角色变化都会改变派生授权
CREATE TRIGGER organization_employment_assignment_authz_bump
    AFTER INSERT OR UPDATE OR DELETE ON organization_employment_assignment
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER organization_reporting_line_authz_bump
    AFTER INSERT OR UPDATE OR DELETE ON organization_reporting_line
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER organization_delegation_authz_bump
    AFTER INSERT OR UPDATE OR DELETE ON organization_delegation
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER organization_owner_role_authz_bump
    AFTER INSERT OR UPDATE OR DELETE ON organization_owner_role
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER organization_employment_authz_bump
    AFTER INSERT OR UPDATE OR DELETE ON organization_employment
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER organization_unit_authz_bump
    AFTER INSERT OR UPDATE OR DELETE ON organization_unit
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER organization_position_authz_bump
    AFTER INSERT OR UPDATE OR DELETE ON organization_position
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER identity_tenant_member_authz_bump
    AFTER INSERT OR UPDATE OR DELETE ON identity_tenant_member
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();
