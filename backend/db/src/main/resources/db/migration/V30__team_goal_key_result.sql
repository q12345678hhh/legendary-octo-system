-- V30 团队目标与关键结果（部门工作区「团队目标」板块）。
--
-- 设计要点：
--   1. 复用 work_goal：scope_type=person 为个人目标（org_unit_id 为空），
--      scope_type=team 为团队目标（必须挂到组织单元）。
--   2. 团队目标进度由关键结果派生（视图层计算），不回写 progress 列。
--   3. KR 使用乐观锁 version；目标删除时级联清理。

ALTER TABLE work_goal
    ADD COLUMN scope_type text NOT NULL DEFAULT 'person',
    ADD COLUMN org_unit_id uuid;

ALTER TABLE work_goal
    ADD CONSTRAINT work_goal_scope_check CHECK (
        (scope_type = 'person' AND org_unit_id IS NULL)
        OR (scope_type = 'team' AND org_unit_id IS NOT NULL)
    );

CREATE INDEX work_goal_unit_idx
    ON work_goal (tenant_id, org_unit_id, status, due_date)
    WHERE scope_type = 'team';

CREATE TABLE work_goal_key_result (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid           NOT NULL REFERENCES tenancy_tenant (id),
    goal_id       uuid           NOT NULL REFERENCES work_goal (id) ON DELETE CASCADE,
    title         text           NOT NULL,
    target_value  numeric(14, 2) NOT NULL,
    current_value numeric(14, 2) NOT NULL DEFAULT 0,
    unit          text           NOT NULL DEFAULT '',
    sort_order    integer        NOT NULL DEFAULT 0,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    version       bigint         NOT NULL DEFAULT 1,
    CONSTRAINT work_goal_key_result_title_check
        CHECK (length(btrim(title)) BETWEEN 1 AND 120),
    CONSTRAINT work_goal_key_result_target_check CHECK (target_value > 0),
    CONSTRAINT work_goal_key_result_current_check CHECK (current_value >= 0),
    CONSTRAINT work_goal_key_result_unit_check CHECK (length(unit) <= 20),
    CONSTRAINT work_goal_key_result_sort_check CHECK (sort_order BETWEEN 0 AND 999)
);

COMMENT ON TABLE work_goal_key_result IS
    '团队目标的关键结果：当前值/目标值派生进度，乐观锁并发控制';

CREATE INDEX work_goal_key_result_goal_idx
    ON work_goal_key_result (tenant_id, goal_id, sort_order);

ALTER TABLE work_goal_key_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_goal_key_result FORCE ROW LEVEL SECURITY;

CREATE POLICY work_goal_key_result_isolation ON work_goal_key_result
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 权限码

INSERT INTO authorization_permission (code, resource_type, action, description, sensitive) VALUES
    ('work.goal.team.read', 'org.unit', 'read', '查看部门团队目标与关键结果', false),
    ('work.goal.team.manage', 'org.unit', 'manage', '维护部门团队目标与关键结果', false)
ON CONFLICT (code) DO NOTHING;
