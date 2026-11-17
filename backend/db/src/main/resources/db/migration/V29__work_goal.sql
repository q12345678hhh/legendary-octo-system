-- V29 个人目标（工作页「目标」板块）。
--
-- 设计要点：
--   1. 目标是租户级个人资源：owner_person_id 为归属人，进度 0-100 由本人维护。
--   2. 首期只支持本人管理与本人工作页展示；团队目标/关键结果后续再扩展。
--   3. 状态只用 active/achieved/archived，不做物理删除。

CREATE TABLE work_goal (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    owner_person_id uuid        NOT NULL,
    title           text        NOT NULL,
    description     text        NOT NULL DEFAULT '',
    period          text        NOT NULL DEFAULT '',
    due_date        date,
    progress        integer     NOT NULL DEFAULT 0,
    status          text        NOT NULL DEFAULT 'active',
    created_by      text        NOT NULL DEFAULT '',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 1,
    CONSTRAINT work_goal_title_check CHECK (length(btrim(title)) BETWEEN 1 AND 120),
    CONSTRAINT work_goal_description_check CHECK (length(description) <= 2000),
    CONSTRAINT work_goal_period_check CHECK (length(period) <= 20),
    CONSTRAINT work_goal_progress_check CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT work_goal_status_check CHECK (status IN ('active', 'achieved', 'archived'))
);

COMMENT ON TABLE work_goal IS
    '个人目标：本人维护进度与状态，工作页只读展示';

CREATE INDEX work_goal_owner_idx
    ON work_goal (tenant_id, owner_person_id, status, due_date);

ALTER TABLE work_goal ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_goal FORCE ROW LEVEL SECURITY;

CREATE POLICY work_goal_isolation ON work_goal
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 权限码

INSERT INTO authorization_permission (code, resource_type, action, description, sensitive) VALUES
    ('work.goal.read', 'work.goal', 'read', '查看本人目标', false),
    ('work.goal.manage', 'work.goal', 'manage', '维护本人目标与进度', false)
ON CONFLICT (code) DO NOTHING;
