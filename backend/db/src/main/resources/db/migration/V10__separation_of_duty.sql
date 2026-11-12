-- V10 职责分离与季度访问复核：权限申请/审批、复核、管理员审计与跨租户复核队列。
--
-- 边界：
-- 1. 规则本身在代码中（SodRuleCatalog），租户只读查看，不能自行放宽；
-- 2. 高权限授予必须引用一条他人批准的申请；仅租户所有者可紧急直授并标记待复核；
-- 3. 复核队列与 platform_organization_due 同款：只存租户与时间，业务数据仍在租户表里；
-- 4. 决策日志与管理员审计都只允许插入，触发器拒绝修改与删除。

-- ------------------------------------------------------------------ 权限目录

INSERT INTO authorization_permission (code, resource_type, action, description, sensitive) VALUES
    ('authorization.access.request', 'authorization.access', 'request', '申请角色与权限', true),
    ('authorization.access.approve', 'authorization.access', 'approve', '批准或驳回权限申请', true),
    ('authorization.review.read', 'authorization.review', 'read', '查看季度访问复核', true),
    ('authorization.review.decide', 'authorization.review', 'decide', '确认或撤销复核条目', true),
    ('authorization.review.manage', 'authorization.review', 'manage', '管理复核、整改冲突与补跑', true),
    ('authorization.admin_action.read', 'authorization.admin_action', 'read', '查看管理员操作审计', true),
    ('payroll.run.prepare', 'payroll.run', 'prepare', '薪酬制单', true),
    ('payroll.run.review', 'payroll.run', 'review', '薪酬复核', true),
    ('payroll.run.approve', 'payroll.run', 'approve', '薪酬批准', true),
    ('payroll.export', 'payroll', 'export', '导出薪酬与付款文件', true),
    ('plugin.upload', 'plugin', 'upload', '上传插件包', true),
    ('plugin.install', 'plugin', 'install', '安装与启用插件', true),
    ('ai.tool.register', 'ai.tool', 'register', '注册 AI 工具', true),
    ('ai.tool.rate', 'ai.tool', 'rate', '为 AI 工具评级', true),
    ('ai.tool.enable', 'ai.tool', 'enable', '启用 AI 工具', true),
    ('module.activation.configure', 'module.activation', 'configure', '配置模块启用参数', true),
    ('module.activation.approve', 'module.activation', 'approve', '批准模块启用', true);

-- ------------------------------------------------------------------ 权限申请

CREATE TABLE authorization_access_request (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               uuid NOT NULL REFERENCES tenancy_tenant (id),
    requester_actor_id      uuid NOT NULL,
    requested_for_actor_id  uuid NOT NULL,
    role_code               text NOT NULL,
    scope_type              text NOT NULL,
    scope_id                uuid,
    valid_to                timestamptz,
    reason                  text NOT NULL,
    state                   text NOT NULL DEFAULT 'pending',
    decided_by_actor_id     uuid,
    decided_at              timestamptz,
    decision_note           text,
    assignment_id           uuid,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_access_request_state_check
        CHECK (state IN ('pending', 'approved', 'rejected', 'cancelled')),
    CONSTRAINT authorization_access_request_role_check
        CHECK (role_code ~ '^[a-z][a-z0-9-]{2,63}$'),
    CONSTRAINT authorization_access_request_reason_check
        CHECK (length(btrim(reason)) > 0),
    CONSTRAINT authorization_access_request_scope_check CHECK (
        (scope_type IN ('tenant', 'self', 'self_created', 'direct_reports', 'reporting_chain')
            AND scope_id IS NULL)
        OR (scope_type IN ('org_unit', 'org_unit_subtree', 'delegated', 'project_space',
                           'legal_entity')
            AND scope_id IS NOT NULL)
    ),
    CONSTRAINT authorization_access_request_decision_check CHECK (
        (state = 'pending'
            AND decided_by_actor_id IS NULL AND decided_at IS NULL AND assignment_id IS NULL)
        OR (state <> 'pending'
            AND decided_by_actor_id IS NOT NULL AND decided_at IS NOT NULL)
    ),
    CONSTRAINT authorization_access_request_assignment_check
        CHECK (assignment_id IS NULL OR state = 'approved'),
    CONSTRAINT authorization_access_request_separation_check CHECK (
        decided_by_actor_id IS NULL
        OR (decided_by_actor_id <> requester_actor_id
            AND decided_by_actor_id <> requested_for_actor_id)
    ),
    CONSTRAINT authorization_access_request_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT authorization_access_request_requester_fk
        FOREIGN KEY (tenant_id, requester_actor_id) REFERENCES identity_actor (tenant_id, id),
    CONSTRAINT authorization_access_request_target_fk
        FOREIGN KEY (tenant_id, requested_for_actor_id) REFERENCES identity_actor (tenant_id, id),
    CONSTRAINT authorization_access_request_decider_fk
        FOREIGN KEY (tenant_id, decided_by_actor_id) REFERENCES identity_actor (tenant_id, id),
    CONSTRAINT authorization_access_request_assignment_fk
        FOREIGN KEY (tenant_id, assignment_id) REFERENCES authorization_assignment (tenant_id, id)
);

COMMENT ON TABLE authorization_access_request IS
    '权限申请：批准必须由申请人以外的人完成；同一主体同一角色同一范围只能有一条待处理申请';

CREATE INDEX authorization_access_request_state_idx
    ON authorization_access_request (tenant_id, state, created_at DESC);
CREATE INDEX authorization_access_request_target_idx
    ON authorization_access_request (tenant_id, requested_for_actor_id, state);

-- 同一主体、角色与范围在待处理状态下只能有一条，避免重复申请堆积
CREATE UNIQUE INDEX authorization_access_request_pending_unique
    ON authorization_access_request
       (tenant_id, requested_for_actor_id, role_code, scope_type,
        coalesce(scope_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE state = 'pending';

ALTER TABLE authorization_access_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_access_request FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_access_request_isolation ON authorization_access_request
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 授权增列

ALTER TABLE authorization_assignment
    ADD COLUMN source_request_id uuid,
    ADD COLUMN review_required boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN authorization_assignment.source_request_id IS
    '高权限双人证据：必须指向一条由他人批准的申请';
COMMENT ON COLUMN authorization_assignment.review_required IS
    '紧急直授标记：即使角色不是高权限，也强制进入下一次季度复核';

ALTER TABLE authorization_assignment
    ADD CONSTRAINT authorization_assignment_source_request_fk
    FOREIGN KEY (tenant_id, source_request_id)
    REFERENCES authorization_access_request (tenant_id, id);

-- ------------------------------------------------------------------ 季度复核

CREATE TABLE authorization_access_review (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenancy_tenant (id),
    period_start date NOT NULL,
    period_end   date NOT NULL,
    state        text NOT NULL DEFAULT 'open',
    due_at       timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    closed_at    timestamptz,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_access_review_state_check
        CHECK (state IN ('open', 'overdue', 'closed')),
    CONSTRAINT authorization_access_review_period_check CHECK (period_end > period_start),
    CONSTRAINT authorization_access_review_closed_check
        CHECK (state <> 'closed' OR closed_at IS NOT NULL),
    CONSTRAINT authorization_access_review_period_unique UNIQUE (tenant_id, period_start),
    CONSTRAINT authorization_access_review_tenant_id_unique UNIQUE (tenant_id, id)
);

COMMENT ON TABLE authorization_access_review IS
    '季度访问复核：每个租户每个季度一份，到期未决先升级，宽限期后自动暂停';

CREATE INDEX authorization_access_review_state_idx
    ON authorization_access_review (tenant_id, state, due_at);

ALTER TABLE authorization_access_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_access_review FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_access_review_isolation ON authorization_access_review
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE authorization_access_review_item (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenancy_tenant (id),
    review_id         uuid NOT NULL,
    assignment_id     uuid NOT NULL,
    subject_actor_id  uuid NOT NULL,
    reviewer_actor_id uuid,
    state             text NOT NULL DEFAULT 'pending',
    decided_at        timestamptz,
    note              text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_access_review_item_state_check
        CHECK (state IN ('pending', 'confirmed', 'revoked')),
    CONSTRAINT authorization_access_review_item_decision_check
        CHECK (state = 'pending' OR decided_at IS NOT NULL),
    CONSTRAINT authorization_access_review_item_reviewer_check
        CHECK (reviewer_actor_id IS NULL OR reviewer_actor_id <> subject_actor_id),
    CONSTRAINT authorization_access_review_item_unique
        UNIQUE (tenant_id, review_id, assignment_id),
    CONSTRAINT authorization_access_review_item_review_fk
        FOREIGN KEY (tenant_id, review_id) REFERENCES authorization_access_review (tenant_id, id)
        ON DELETE CASCADE,
    CONSTRAINT authorization_access_review_item_assignment_fk
        FOREIGN KEY (tenant_id, assignment_id) REFERENCES authorization_assignment (tenant_id, id),
    CONSTRAINT authorization_access_review_item_subject_fk
        FOREIGN KEY (tenant_id, subject_actor_id) REFERENCES identity_actor (tenant_id, id),
    CONSTRAINT authorization_access_review_item_reviewer_fk
        FOREIGN KEY (tenant_id, reviewer_actor_id) REFERENCES identity_actor (tenant_id, id)
);

COMMENT ON TABLE authorization_access_review_item IS
    '复核条目：复核人不能复核自己；逾期自动暂停仍保留 revoked 证据';

CREATE INDEX authorization_access_review_item_reviewer_idx
    ON authorization_access_review_item (tenant_id, reviewer_actor_id, state);
CREATE INDEX authorization_access_review_item_review_idx
    ON authorization_access_review_item (tenant_id, review_id, state);

ALTER TABLE authorization_access_review_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_access_review_item FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_access_review_item_isolation ON authorization_access_review_item
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 管理员操作审计

CREATE TABLE authorization_admin_action (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenancy_tenant (id),
    actor_id    uuid,
    action      text NOT NULL,
    target_type text NOT NULL,
    target_id   uuid,
    detail      jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT authorization_admin_action_action_check
        CHECK (length(btrim(action)) BETWEEN 1 AND 100),
    CONSTRAINT authorization_admin_action_target_check
        CHECK (length(btrim(target_type)) BETWEEN 1 AND 100),
    CONSTRAINT authorization_admin_action_detail_check CHECK (jsonb_typeof(detail) = 'object'),
    CONSTRAINT authorization_admin_action_tenant_id_unique UNIQUE (tenant_id, id)
);

COMMENT ON TABLE authorization_admin_action IS
    '管理员操作审计：与决策日志一样只允许插入，管理员不能修改自己的操作记录';

CREATE INDEX authorization_admin_action_occurred_idx
    ON authorization_admin_action (tenant_id, occurred_at DESC);
CREATE INDEX authorization_admin_action_actor_idx
    ON authorization_admin_action (tenant_id, actor_id, occurred_at DESC);

CREATE FUNCTION authorization_admin_action_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'authorization_admin_action 只允许插入，不允许修改或删除';
END
$$;

CREATE TRIGGER authorization_admin_action_immutable_trigger
    BEFORE UPDATE OR DELETE ON authorization_admin_action
    FOR EACH ROW EXECUTE FUNCTION authorization_admin_action_immutable();

ALTER TABLE authorization_admin_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_admin_action FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_admin_action_isolation ON authorization_admin_action
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 版本触发

CREATE TRIGGER authorization_access_request_bump
    AFTER INSERT OR UPDATE OR DELETE ON authorization_access_request
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER authorization_access_review_bump
    AFTER INSERT OR UPDATE OR DELETE ON authorization_access_review
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER authorization_access_review_item_bump
    AFTER INSERT OR UPDATE OR DELETE ON authorization_access_review_item
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

CREATE TRIGGER authorization_admin_action_bump
    AFTER INSERT OR UPDATE OR DELETE ON authorization_admin_action
    FOR EACH ROW EXECUTE FUNCTION authorization_bump_version();

-- ------------------------------------------------------------------ 复核队列

-- Worker 需要跨租户发现「哪些租户该生成季度复核」，租户复核表受 FORCE RLS 保护，
-- 因此与 platform_organization_due 一样单独维护一张系统级队列；业务内容仍在租户表里。
CREATE TABLE platform_access_review_due (
    tenant_id   uuid PRIMARY KEY,
    next_run_at timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE platform_access_review_due IS
    '季度复核到期队列：只用于 Worker 跨租户发现待生成租户，不承载任何租户业务字段';

CREATE INDEX platform_access_review_due_run_idx
    ON platform_access_review_due (next_run_at);

INSERT INTO platform_access_review_due (tenant_id, next_run_at)
SELECT id, date_trunc('quarter', now()) + interval '3 months'
  FROM tenancy_tenant
ON CONFLICT (tenant_id) DO NOTHING;

CREATE FUNCTION platform_access_review_due_seed() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO platform_access_review_due (tenant_id, next_run_at)
    VALUES (NEW.id, date_trunc('quarter', now()) + interval '3 months')
    ON CONFLICT (tenant_id) DO NOTHING;
    RETURN NULL;
END
$$;

COMMENT ON FUNCTION platform_access_review_due_seed() IS
    '新租户自动播种下一次季度复核时间，避免 Worker 依赖业务表扫描';

CREATE TRIGGER tenancy_tenant_access_review_seed
    AFTER INSERT ON tenancy_tenant
    FOR EACH ROW EXECUTE FUNCTION platform_access_review_due_seed();
