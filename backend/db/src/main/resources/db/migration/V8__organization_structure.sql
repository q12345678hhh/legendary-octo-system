-- V8 组织结构与生效日期：法人、部门树、职级、岗位、职位、成本中心、地点，
-- 主/兼岗、实线/虚线汇报、管理委派、老板角色，以及变更单、不可变历史与影响预览票据。
--
-- 边界：
-- 1. 结构实体保存「当前态」，未来变更进入 organization_change，由平台 Worker 到期应用；
-- 2. 关系实体（主兼岗、汇报线、委派、老板角色）直接带 effective_from/effective_to，
--    「当前生效」= effective_from <= today AND (effective_to IS NULL OR effective_to > today)；
-- 3. 所有跨租户引用都使用 (tenant_id, id) 复合外键：即使应用层漏判，跨租户 UUID 也写不进去；
-- 4. 每张租户表照抄 backend/db/templates/tenant-table.template.sql：ENABLE + FORCE RLS，
--    策略统一为 tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid。

-- 复合外键要求被引用列组唯一。id 本身是主键，这里补的 (tenant_id, id) 唯一约束
-- 只用于让「同租户内引用」在数据库层成立，不改变既有数据与唯一性语义。
ALTER TABLE organization_person
    ADD CONSTRAINT organization_person_tenant_id_unique UNIQUE (tenant_id, id);
ALTER TABLE organization_employment
    ADD CONSTRAINT organization_employment_tenant_id_unique UNIQUE (tenant_id, id);

-- ------------------------------------------------------------------ 结构实体

CREATE TABLE organization_legal_entity (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    code            text        NOT NULL,
    name            text        NOT NULL,
    registration_no text,
    status          text        NOT NULL DEFAULT 'active',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_legal_entity_status_check CHECK (status IN ('active', 'inactive')),
    CONSTRAINT organization_legal_entity_code_check CHECK (length(btrim(code)) BETWEEN 1 AND 64),
    CONSTRAINT organization_legal_entity_name_check CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT organization_legal_entity_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT organization_legal_entity_tenant_id_unique UNIQUE (tenant_id, id)
);

COMMENT ON TABLE organization_legal_entity IS
    '法人主体：部门、成本中心与跨法人审批判断的归属依据；停用不删除历史引用';

CREATE INDEX organization_legal_entity_tenant_idx ON organization_legal_entity (tenant_id, status);

ALTER TABLE organization_legal_entity ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_legal_entity FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_legal_entity_isolation ON organization_legal_entity
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE organization_unit (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    parent_id       uuid,
    legal_entity_id uuid        NOT NULL,
    code            text        NOT NULL,
    name            text        NOT NULL,
    unit_type       text        NOT NULL DEFAULT 'department',
    status          text        NOT NULL DEFAULT 'active',
    sort_order      integer     NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_unit_type_check
        CHECK (unit_type IN ('company', 'division', 'department', 'team')),
    CONSTRAINT organization_unit_status_check CHECK (status IN ('active', 'archived')),
    CONSTRAINT organization_unit_code_check CHECK (length(btrim(code)) BETWEEN 1 AND 64),
    CONSTRAINT organization_unit_name_check CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT organization_unit_sort_check CHECK (sort_order >= 0),
    CONSTRAINT organization_unit_not_self CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT organization_unit_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT organization_unit_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT organization_unit_parent_fk FOREIGN KEY (tenant_id, parent_id)
        REFERENCES organization_unit (tenant_id, id),
    CONSTRAINT organization_unit_legal_entity_fk FOREIGN KEY (tenant_id, legal_entity_id)
        REFERENCES organization_legal_entity (tenant_id, id)
);

COMMENT ON TABLE organization_unit IS
    '部门树：parent_id 为空表示根；跨租户父级由 (tenant_id, parent_id) 复合外键直接拒绝';
COMMENT ON COLUMN organization_unit.status IS
    'archived 表示已归档：不允许挂新职位或子部门，但历史引用与历史快照保留';

CREATE INDEX organization_unit_parent_idx ON organization_unit (tenant_id, parent_id);
CREATE INDEX organization_unit_legal_entity_idx ON organization_unit (tenant_id, legal_entity_id);

ALTER TABLE organization_unit ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_unit FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_unit_isolation ON organization_unit
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE organization_job_level (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenancy_tenant (id),
    code       text        NOT NULL,
    name       text        NOT NULL,
    rank       integer     NOT NULL,
    status     text        NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_job_level_rank_check CHECK (rank >= 1),
    CONSTRAINT organization_job_level_status_check CHECK (status IN ('active', 'inactive')),
    CONSTRAINT organization_job_level_code_check CHECK (length(btrim(code)) BETWEEN 1 AND 64),
    CONSTRAINT organization_job_level_name_check CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT organization_job_level_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT organization_job_level_tenant_id_unique UNIQUE (tenant_id, id)
);

COMMENT ON TABLE organization_job_level IS
    '职级：rank 从 1 起递增，数值越大级别越高；停用不影响既有职位引用';

CREATE INDEX organization_job_level_rank_idx ON organization_job_level (tenant_id, rank);

ALTER TABLE organization_job_level ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_job_level FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_job_level_isolation ON organization_job_level
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE organization_job (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    code             text        NOT NULL,
    name             text        NOT NULL,
    job_family       text,
    default_level_id uuid,
    status           text        NOT NULL DEFAULT 'active',
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_job_status_check CHECK (status IN ('active', 'inactive')),
    CONSTRAINT organization_job_code_check CHECK (length(btrim(code)) BETWEEN 1 AND 64),
    CONSTRAINT organization_job_name_check CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT organization_job_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT organization_job_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT organization_job_level_fk FOREIGN KEY (tenant_id, default_level_id)
        REFERENCES organization_job_level (tenant_id, id)
);

COMMENT ON TABLE organization_job IS
    '岗位定义（职务）：与具体部门解耦，如「后端工程师」；职位 organization_position 才是部门下的编制占位';

CREATE INDEX organization_job_family_idx ON organization_job (tenant_id, job_family);

ALTER TABLE organization_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_job FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_job_isolation ON organization_job
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE organization_position (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    org_unit_id     uuid        NOT NULL,
    job_id          uuid        NOT NULL,
    code            text        NOT NULL,
    name            text,
    headcount_limit integer,
    status          text        NOT NULL DEFAULT 'active',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_position_status_check CHECK (status IN ('active', 'inactive')),
    CONSTRAINT organization_position_code_check CHECK (length(btrim(code)) BETWEEN 1 AND 64),
    CONSTRAINT organization_position_name_check
        CHECK (name IS NULL OR length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT organization_position_headcount_check
        CHECK (headcount_limit IS NULL OR headcount_limit >= 0),
    CONSTRAINT organization_position_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT organization_position_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT organization_position_unit_fk FOREIGN KEY (tenant_id, org_unit_id)
        REFERENCES organization_unit (tenant_id, id),
    CONSTRAINT organization_position_job_fk FOREIGN KEY (tenant_id, job_id)
        REFERENCES organization_job (tenant_id, id)
);

COMMENT ON TABLE organization_position IS
    '职位：部门下的具体编制占位，主/兼岗 assignment 指向它；部门归档前必须没有 active 职位';
COMMENT ON COLUMN organization_position.headcount_limit IS
    '编制上限，可空表示未设限；与在职人数一起进入部门移动/调整的影响预览';

CREATE INDEX organization_position_unit_idx ON organization_position (tenant_id, org_unit_id, status);
CREATE INDEX organization_position_job_idx ON organization_position (tenant_id, job_id);

ALTER TABLE organization_position ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_position FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_position_isolation ON organization_position
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE organization_cost_center (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    code            text        NOT NULL,
    name            text        NOT NULL,
    legal_entity_id uuid        NOT NULL,
    status          text        NOT NULL DEFAULT 'active',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_cost_center_status_check CHECK (status IN ('active', 'inactive')),
    CONSTRAINT organization_cost_center_code_check CHECK (length(btrim(code)) BETWEEN 1 AND 64),
    CONSTRAINT organization_cost_center_name_check CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT organization_cost_center_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT organization_cost_center_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT organization_cost_center_legal_entity_fk FOREIGN KEY (tenant_id, legal_entity_id)
        REFERENCES organization_legal_entity (tenant_id, id)
);

COMMENT ON TABLE organization_cost_center IS
    '成本中心：必须归属一个法人；报表与核算维度在 Phase 7 接入，这里只固定主数据';

CREATE INDEX organization_cost_center_legal_entity_idx
    ON organization_cost_center (tenant_id, legal_entity_id);

ALTER TABLE organization_cost_center ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_cost_center FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_cost_center_isolation ON organization_cost_center
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE organization_location (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenancy_tenant (id),
    code       text        NOT NULL,
    name       text        NOT NULL,
    address    text,
    timezone   text        NOT NULL DEFAULT 'Asia/Shanghai',
    status     text        NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_location_status_check CHECK (status IN ('active', 'inactive')),
    CONSTRAINT organization_location_code_check CHECK (length(btrim(code)) BETWEEN 1 AND 64),
    CONSTRAINT organization_location_name_check CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT organization_location_timezone_check
        CHECK (length(btrim(timezone)) BETWEEN 1 AND 64),
    CONSTRAINT organization_location_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT organization_location_tenant_id_unique UNIQUE (tenant_id, id)
);

COMMENT ON TABLE organization_location IS
    '办公地点：时区用于考勤与排班的本地日换算，与租户时区可以不同';

ALTER TABLE organization_location ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_location FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_location_isolation ON organization_location
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 关系实体（生效区间）

CREATE TABLE organization_employment_assignment (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenancy_tenant (id),
    employment_id  uuid        NOT NULL,
    position_id    uuid        NOT NULL,
    assignment_type text       NOT NULL DEFAULT 'primary',
    effective_from date        NOT NULL,
    effective_to   date,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_employment_assignment_type_check
        CHECK (assignment_type IN ('primary', 'secondary')),
    CONSTRAINT organization_employment_assignment_period_check
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT organization_employment_assignment_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT organization_employment_assignment_employment_fk
        FOREIGN KEY (tenant_id, employment_id) REFERENCES organization_employment (tenant_id, id),
    CONSTRAINT organization_employment_assignment_position_fk
        FOREIGN KEY (tenant_id, position_id) REFERENCES organization_position (tenant_id, id)
);

COMMENT ON TABLE organization_employment_assignment IS
    '主/兼岗：同一 Employment 同一时刻最多一条生效主岗，兼岗可多条；区间重叠由服务层拒绝';

-- 主岗部分唯一：只约束「未结束」的主岗行，历史上已结束的调动不参与
CREATE UNIQUE INDEX organization_employment_assignment_primary_unique
    ON organization_employment_assignment (tenant_id, employment_id)
    WHERE assignment_type = 'primary' AND effective_to IS NULL;
CREATE INDEX organization_employment_assignment_employment_idx
    ON organization_employment_assignment (tenant_id, employment_id, effective_from DESC);
CREATE INDEX organization_employment_assignment_position_idx
    ON organization_employment_assignment (tenant_id, position_id, effective_to);

ALTER TABLE organization_employment_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_employment_assignment FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_employment_assignment_isolation ON organization_employment_assignment
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE organization_reporting_line (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 uuid        NOT NULL REFERENCES tenancy_tenant (id),
    subordinate_employment_id uuid        NOT NULL,
    manager_employment_id     uuid        NOT NULL,
    line_type                 text        NOT NULL DEFAULT 'solid',
    effective_from            date        NOT NULL,
    effective_to              date,
    created_by                text        NOT NULL,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_reporting_line_type_check CHECK (line_type IN ('solid', 'dotted')),
    CONSTRAINT organization_reporting_line_not_self_check
        CHECK (subordinate_employment_id <> manager_employment_id),
    CONSTRAINT organization_reporting_line_period_check
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT organization_reporting_line_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT organization_reporting_line_subordinate_fk
        FOREIGN KEY (tenant_id, subordinate_employment_id)
        REFERENCES organization_employment (tenant_id, id),
    CONSTRAINT organization_reporting_line_manager_fk
        FOREIGN KEY (tenant_id, manager_employment_id)
        REFERENCES organization_employment (tenant_id, id)
);

COMMENT ON TABLE organization_reporting_line IS
    '汇报线：solid 实线、dotted 虚线；同一员工同一时刻最多一条生效实线，虚线可多条但不允许成环';

-- 实线部分唯一：虚线不受限，历史已结束的实线不参与
CREATE UNIQUE INDEX organization_reporting_line_solid_unique
    ON organization_reporting_line (tenant_id, subordinate_employment_id)
    WHERE line_type = 'solid' AND effective_to IS NULL;
CREATE INDEX organization_reporting_line_manager_idx
    ON organization_reporting_line (tenant_id, manager_employment_id, line_type);

ALTER TABLE organization_reporting_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_reporting_line FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_reporting_line_isolation ON organization_reporting_line
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE organization_delegation (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                uuid        NOT NULL REFERENCES tenancy_tenant (id),
    delegator_employment_id  uuid        NOT NULL,
    delegate_employment_id   uuid        NOT NULL,
    scope_org_unit_id        uuid        NOT NULL,
    reason                   text        NOT NULL,
    effective_from           date        NOT NULL,
    effective_to             date        NOT NULL,
    created_by               text        NOT NULL,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_delegation_not_self_check
        CHECK (delegator_employment_id <> delegate_employment_id),
    CONSTRAINT organization_delegation_period_check CHECK (effective_to > effective_from),
    CONSTRAINT organization_delegation_reason_check CHECK (length(btrim(reason)) > 0),
    CONSTRAINT organization_delegation_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT organization_delegation_delegator_fk
        FOREIGN KEY (tenant_id, delegator_employment_id)
        REFERENCES organization_employment (tenant_id, id),
    CONSTRAINT organization_delegation_delegate_fk
        FOREIGN KEY (tenant_id, delegate_employment_id)
        REFERENCES organization_employment (tenant_id, id),
    CONSTRAINT organization_delegation_scope_fk FOREIGN KEY (tenant_id, scope_org_unit_id)
        REFERENCES organization_unit (tenant_id, id)
);

COMMENT ON TABLE organization_delegation IS
    '管理委派（代管）：必须限时并填写原因，最长天数由 platform.organization.max-delegation-days 控制';
COMMENT ON COLUMN organization_delegation.scope_org_unit_id IS
    '委派范围：当前只支持某个部门子树；跨部门/跨法人范围随协作空间扩展';

CREATE INDEX organization_delegation_scope_idx
    ON organization_delegation (tenant_id, scope_org_unit_id, effective_to);
CREATE INDEX organization_delegation_delegate_idx
    ON organization_delegation (tenant_id, delegate_employment_id, effective_to);

ALTER TABLE organization_delegation ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_delegation FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_delegation_isolation ON organization_delegation
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE organization_owner_role (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenancy_tenant (id),
    employment_id     uuid        NOT NULL,
    role_type         text        NOT NULL,
    effective_from    date        NOT NULL,
    effective_to      date,
    appointed_by      text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_owner_role_type_check CHECK (role_type IN ('owner', 'trustee')),
    CONSTRAINT organization_owner_role_period_check
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT organization_owner_role_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT organization_owner_role_employment_fk FOREIGN KEY (tenant_id, employment_id)
        REFERENCES organization_employment (tenant_id, id)
);

COMMENT ON TABLE organization_owner_role IS
    '老板/最高负责人（owner）与受托人（trustee）：独立业务角色，与租户管理员身份无关；'
    '管理员不会因为 is_admin 自动获得组织正文权限';

CREATE UNIQUE INDEX organization_owner_role_active_unique
    ON organization_owner_role (tenant_id, employment_id, role_type)
    WHERE effective_to IS NULL;
CREATE INDEX organization_owner_role_type_idx ON organization_owner_role (tenant_id, role_type, effective_to);

ALTER TABLE organization_owner_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_owner_role FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_owner_role_isolation ON organization_owner_role
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 变更、历史与预览

CREATE TABLE organization_change (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    change_type   text        NOT NULL,
    target_id     uuid,
    effective_date date       NOT NULL,
    payload       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status        text        NOT NULL DEFAULT 'scheduled',
    requested_by  text        NOT NULL,
    reason        text,
    applied_at    timestamptz,
    failure_reason text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_change_type_check
        CHECK (change_type IN ('unit_create', 'unit_update', 'unit_move', 'unit_archive',
                               'legal_entity_upsert', 'job_upsert', 'job_level_upsert',
                               'position_upsert', 'cost_center_upsert', 'location_upsert',
                               'owner_assign', 'owner_revoke')),
    CONSTRAINT organization_change_status_check
        CHECK (status IN ('scheduled', 'applied', 'cancelled', 'failed')),
    CONSTRAINT organization_change_payload_check CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT organization_change_tenant_id_unique UNIQUE (tenant_id, id)
);

COMMENT ON TABLE organization_change IS
    '未来变更单：只有 scheduled 可以被应用；到期由 boot-worker 逐租户认领并原子切换当前态';
COMMENT ON COLUMN organization_change.payload IS
    '变更参数快照：应用时以它为准重算，不读取创建时刻的当前态，避免请求被改后走样';

CREATE INDEX organization_change_due_idx
    ON organization_change (tenant_id, status, effective_date);
CREATE INDEX organization_change_target_idx
    ON organization_change (tenant_id, target_id, created_at DESC);

ALTER TABLE organization_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_change FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_change_isolation ON organization_change
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE organization_change_history (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    change_id     uuid,
    entity_type   text        NOT NULL,
    entity_id     uuid        NOT NULL,
    action        text        NOT NULL,
    effective_date date       NOT NULL,
    before_state  jsonb,
    after_state   jsonb,
    actor         text        NOT NULL,
    occurred_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organization_change_history_entity_check
        CHECK (length(btrim(entity_type)) BETWEEN 1 AND 64),
    CONSTRAINT organization_change_history_action_check
        CHECK (length(btrim(action)) BETWEEN 1 AND 64),
    CONSTRAINT organization_change_history_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT organization_change_history_change_fk FOREIGN KEY (tenant_id, change_id)
        REFERENCES organization_change (tenant_id, id)
);

COMMENT ON TABLE organization_change_history IS
    '组织变更历史：只允许插入，触发器阻止 UPDATE/DELETE；before/after 保存完整快照用于追责';

CREATE INDEX organization_change_history_entity_idx
    ON organization_change_history (tenant_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX organization_change_history_tenant_idx
    ON organization_change_history (tenant_id, occurred_at DESC);

CREATE FUNCTION organization_change_history_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'organization_change_history 只允许插入，不允许修改或删除';
END
$$;

CREATE TRIGGER organization_change_history_immutable_trigger
    BEFORE UPDATE OR DELETE ON organization_change_history
    FOR EACH ROW EXECUTE FUNCTION organization_change_history_immutable();

ALTER TABLE organization_change_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_change_history FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_change_history_isolation ON organization_change_history
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE organization_impact_preview (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              uuid        NOT NULL REFERENCES tenancy_tenant (id),
    unit_id                uuid        NOT NULL,
    target_parent_id       uuid,
    descendant_count       integer     NOT NULL DEFAULT 0,
    employment_count       integer     NOT NULL DEFAULT 0,
    position_count         integer     NOT NULL DEFAULT 0,
    reporting_line_count   integer     NOT NULL DEFAULT 0,
    delegation_count       integer     NOT NULL DEFAULT 0,
    scheduled_change_count integer     NOT NULL DEFAULT 0,
    created_by             text        NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    expires_at             timestamptz NOT NULL,
    consumed_at            timestamptz,
    CONSTRAINT organization_impact_preview_counts_check
        CHECK (descendant_count >= 0 AND employment_count >= 0 AND position_count >= 0
               AND reporting_line_count >= 0 AND delegation_count >= 0
               AND scheduled_change_count >= 0),
    CONSTRAINT organization_impact_preview_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT organization_impact_preview_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT organization_impact_preview_unit_fk FOREIGN KEY (tenant_id, unit_id)
        REFERENCES organization_unit (tenant_id, id),
    CONSTRAINT organization_impact_preview_parent_fk FOREIGN KEY (tenant_id, target_parent_id)
        REFERENCES organization_unit (tenant_id, id)
);

COMMENT ON TABLE organization_impact_preview IS
    '部门移动影响预览票据：10 分钟过期、单次消费；移动时服务端复算并比对，数量变化即拒绝';
COMMENT ON COLUMN organization_impact_preview.consumed_at IS
    '消费时间：非空表示票据已用于一次移动，重放会被拒绝';

CREATE INDEX organization_impact_preview_unit_idx
    ON organization_impact_preview (tenant_id, unit_id, created_at DESC);

ALTER TABLE organization_impact_preview ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_impact_preview FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_impact_preview_isolation ON organization_impact_preview
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 到期应用队列

-- Worker 必须先「发现哪些租户有到期变更」，再去对应租户上下文里应用。
-- 租户表都是 FORCE RLS，没有上下文时一行也看不到，所以这里单独维护一张系统级队列：
-- 它只保存 change_id（业务内容仍在受 RLS 保护的 organization_change 里）与到期日，
-- 和 platform_outbox 一样由平台内部使用，不承载任何可被租户读写的业务字段。
CREATE TABLE platform_organization_due (
    change_id     uuid PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    effective_date date       NOT NULL,
    enqueued_at   timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE platform_organization_due IS
    '组织变更到期队列：只用于 Worker 跨租户发现到期变更；业务数据与权限判定仍在 organization_change';

CREATE INDEX platform_organization_due_date_idx
    ON platform_organization_due (effective_date);

