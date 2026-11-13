-- V17 通用工作流与审批。
--
-- 设计要点：
--   1. 定义按 (tenant, key, version) 版本化；发布后 schema/version/key 不可改，只能退役；
--      同一 key 最多一个 published 版本（部分唯一索引）。
--   2. 实例固定 definition_id + definition_version，发布新版本不影响运行中实例。
--   3. 令牌表记录并行分支；任务表同时承载「实例审批任务」与「工时/周报直连审核任务」
--      （直连任务的 instance_id/token_id/node_key 为空）。
--   4. 状态流转日志只追加：触发器禁止 UPDATE/DELETE。
--   5. 所有表启用并强制 RLS；每表恰好一条租户隔离策略。

CREATE TABLE workflow_definition (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    key           text        NOT NULL,
    version       integer     NOT NULL,
    name          text        NOT NULL,
    status        text        NOT NULL DEFAULT 'draft',
    schema        jsonb       NOT NULL,
    schema_hash   text        NOT NULL,
    created_by    text        NOT NULL,
    published_at  timestamptz,
    retired_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflow_definition_key_check CHECK (key ~ '^[a-z][a-z0-9._-]{2,63}$'),
    CONSTRAINT workflow_definition_version_check CHECK (version >= 1),
    CONSTRAINT workflow_definition_status_check
        CHECK (status IN ('draft', 'published', 'retired')),
    CONSTRAINT workflow_definition_schema_check CHECK (jsonb_typeof(schema) = 'object'),
    CONSTRAINT workflow_definition_name_check CHECK (length(name) BETWEEN 1 AND 200),
    CONSTRAINT workflow_definition_unique UNIQUE (tenant_id, key, version)
);

COMMENT ON TABLE workflow_definition IS
    '工作流定义：draft 可改；published 后 schema/version/key 由触发器冻结；同一 key 只允许一个 published';

CREATE UNIQUE INDEX workflow_definition_one_published
    ON workflow_definition (tenant_id, key) WHERE status = 'published';
CREATE INDEX workflow_definition_list_idx
    ON workflow_definition (tenant_id, key, version DESC);

ALTER TABLE workflow_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_definition FORCE ROW LEVEL SECURITY;

CREATE POLICY workflow_definition_isolation ON workflow_definition
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION workflow_definition_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'published' THEN
        IF NEW.schema IS DISTINCT FROM OLD.schema
           OR NEW.schema_hash <> OLD.schema_hash
           OR NEW.version <> OLD.version
           OR NEW.key <> OLD.key THEN
            RAISE EXCEPTION '已发布的工作流定义不可修改 schema/version/key';
        END IF;
        IF NEW.status NOT IN ('published', 'retired') THEN
            RAISE EXCEPTION '已发布的工作流定义只能转为 retired';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER workflow_definition_published_immutable
    BEFORE UPDATE ON workflow_definition
    FOR EACH ROW EXECUTE FUNCTION workflow_definition_guard();

CREATE TABLE workflow_instance (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid        NOT NULL REFERENCES tenancy_tenant (id),
    definition_id         uuid        NOT NULL REFERENCES workflow_definition (id),
    definition_version    integer     NOT NULL,
    definition_key        text        NOT NULL,
    business_type         text        NOT NULL,
    business_id           uuid,
    business_key          text,
    status                text        NOT NULL DEFAULT 'running',
    variables             jsonb       NOT NULL DEFAULT '{}'::jsonb,
    started_by_person_id  uuid        NOT NULL,
    started_at            timestamptz NOT NULL DEFAULT now(),
    ended_at              timestamptz,
    CONSTRAINT workflow_instance_status_check
        CHECK (status IN ('running', 'completed', 'returned', 'withdrawn',
                          'cancelled', 'failed')),
    CONSTRAINT workflow_instance_version_check CHECK (definition_version >= 1),
    CONSTRAINT workflow_instance_variables_check CHECK (jsonb_typeof(variables) = 'object'),
    CONSTRAINT workflow_instance_end_check CHECK (
        status = 'running' OR ended_at IS NOT NULL)
);

COMMENT ON TABLE workflow_instance IS
    '流程实例：冻结定义版本与 key；business_key 用于业务侧幂等启动';

CREATE UNIQUE INDEX workflow_instance_business_key
    ON workflow_instance (tenant_id, business_type, business_key)
    WHERE business_key IS NOT NULL;
CREATE INDEX workflow_instance_status_idx
    ON workflow_instance (tenant_id, status, started_at DESC);
CREATE INDEX workflow_instance_business_idx
    ON workflow_instance (tenant_id, business_type, business_id);
CREATE INDEX workflow_instance_starter_idx
    ON workflow_instance (tenant_id, started_by_person_id, started_at DESC);

ALTER TABLE workflow_instance ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_instance FORCE ROW LEVEL SECURITY;

CREATE POLICY workflow_instance_isolation ON workflow_instance
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE workflow_token (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenancy_tenant (id),
    instance_id  uuid        NOT NULL REFERENCES workflow_instance (id) ON DELETE CASCADE,
    node_key     text        NOT NULL,
    status       text        NOT NULL DEFAULT 'active',
    created_at   timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT workflow_token_status_check
        CHECK (status IN ('active', 'waiting', 'completed', 'cancelled'))
);

COMMENT ON TABLE workflow_token IS
    '执行令牌：并行分支每个分支一行；join 节点等待同一节点上的令牌全部到齐';

CREATE INDEX workflow_token_instance_idx
    ON workflow_token (tenant_id, instance_id, status);

ALTER TABLE workflow_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_token FORCE ROW LEVEL SECURITY;

CREATE POLICY workflow_token_isolation ON workflow_token
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE workflow_task (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid        NOT NULL REFERENCES tenancy_tenant (id),
    instance_id         uuid        REFERENCES workflow_instance (id) ON DELETE CASCADE,
    token_id            uuid        REFERENCES workflow_token (id) ON DELETE SET NULL,
    node_key            text,
    business_type       text        NOT NULL,
    business_id         uuid,
    task_type           text        NOT NULL DEFAULT 'approval',
    title               text        NOT NULL,
    assignee_person_id  uuid,
    assignee_kind       text        NOT NULL DEFAULT 'person',
    assignee_role       text,
    candidates          jsonb       NOT NULL DEFAULT '[]'::jsonb,
    status              text        NOT NULL DEFAULT 'pending',
    action              text,
    comment             text,
    due_at              timestamptz,
    sla_state           text        NOT NULL DEFAULT 'normal',
    claimed_at          timestamptz,
    claimed_by          uuid,
    completed_at        timestamptz,
    completed_by        uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflow_task_type_check
        CHECK (task_type IN ('approval', 'review', 'service', 'revise')),
    CONSTRAINT workflow_task_assignee_kind_check CHECK (assignee_kind IN (
        'person', 'position', 'unit_head', 'reporting_chain', 'role',
        'business_owner', 'direct')),
    CONSTRAINT workflow_task_status_check CHECK (status IN (
        'pending', 'claimed', 'completed', 'transferred', 'returned', 'cancelled')),
    CONSTRAINT workflow_task_sla_state_check
        CHECK (sla_state IN ('normal', 'warning', 'breached')),
    CONSTRAINT workflow_task_candidates_check CHECK (jsonb_typeof(candidates) = 'array'),
    CONSTRAINT workflow_task_title_check CHECK (length(title) BETWEEN 1 AND 300)
);

COMMENT ON TABLE workflow_task IS
    '审批任务：实例任务带 instance_id；工时/周报直连审核任务三者（instance/token/node）为空';

CREATE INDEX workflow_task_assignee_idx
    ON workflow_task (tenant_id, assignee_person_id, status)
    WHERE status IN ('pending', 'claimed');
CREATE INDEX workflow_task_business_idx
    ON workflow_task (tenant_id, business_type, business_id);
CREATE INDEX workflow_task_due_idx
    ON workflow_task (tenant_id, due_at)
    WHERE status IN ('pending', 'claimed');
CREATE UNIQUE INDEX workflow_task_direct_unique
    ON workflow_task (tenant_id, business_type, business_id, task_type)
    WHERE instance_id IS NULL AND business_id IS NOT NULL;

ALTER TABLE workflow_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_task FORCE ROW LEVEL SECURITY;

CREATE POLICY workflow_task_isolation ON workflow_task
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE workflow_transition_log (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    instance_id      uuid        NOT NULL REFERENCES workflow_instance (id) ON DELETE CASCADE,
    task_id          uuid,
    node_key         text,
    from_status      text,
    to_status        text,
    action           text        NOT NULL,
    actor_person_id  uuid,
    comment          text,
    occurred_at      timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE workflow_transition_log IS
    '状态流转日志：只追加，UPDATE/DELETE 由触发器拒绝';

CREATE INDEX workflow_transition_log_instance_idx
    ON workflow_transition_log (tenant_id, instance_id, occurred_at);

ALTER TABLE workflow_transition_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_transition_log FORCE ROW LEVEL SECURITY;

CREATE POLICY workflow_transition_log_isolation ON workflow_transition_log
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION workflow_transition_log_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'workflow_transition_log 只允许追加，不允许修改或删除';
END $$;

CREATE TRIGGER workflow_transition_log_immutable_trigger
    BEFORE UPDATE OR DELETE ON workflow_transition_log
    FOR EACH ROW EXECUTE FUNCTION workflow_transition_log_immutable();

CREATE TABLE workflow_timer (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    instance_id uuid        NOT NULL REFERENCES workflow_instance (id) ON DELETE CASCADE,
    token_id    uuid        NOT NULL REFERENCES workflow_token (id) ON DELETE CASCADE,
    node_key    text        NOT NULL,
    fire_at     timestamptz NOT NULL,
    status      text        NOT NULL DEFAULT 'pending',
    attempts    integer     NOT NULL DEFAULT 0,
    last_error  text,
    fired_at    timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflow_timer_status_check
        CHECK (status IN ('pending', 'fired', 'cancelled')),
    CONSTRAINT workflow_timer_attempts_check CHECK (attempts >= 0),
    CONSTRAINT workflow_timer_unique UNIQUE (tenant_id, instance_id, token_id, node_key)
);

COMMENT ON TABLE workflow_timer IS
    '定时器：同一令牌同一节点只建一个；到点后由 Worker 认领并推进令牌';

CREATE INDEX workflow_timer_due_idx
    ON workflow_timer (tenant_id, status, fire_at);

ALTER TABLE workflow_timer ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_timer FORCE ROW LEVEL SECURITY;

CREATE POLICY workflow_timer_isolation ON workflow_timer
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE workflow_service_task (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    instance_id     uuid        NOT NULL REFERENCES workflow_instance (id) ON DELETE CASCADE,
    token_id        uuid        REFERENCES workflow_token (id) ON DELETE SET NULL,
    node_key        text        NOT NULL,
    handler_key     text        NOT NULL,
    idempotency_key text        NOT NULL,
    status          text        NOT NULL DEFAULT 'pending',
    attempts        integer     NOT NULL DEFAULT 0,
    max_attempts    integer     NOT NULL DEFAULT 3,
    last_error      text,
    compensation_of uuid        REFERENCES workflow_service_task (id),
    payload         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflow_service_task_status_check
        CHECK (status IN ('pending', 'running', 'succeeded', 'failed', 'compensated')),
    CONSTRAINT workflow_service_task_attempts_check
        CHECK (attempts >= 0 AND max_attempts BETWEEN 1 AND 10),
    CONSTRAINT workflow_service_task_payload_check CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT workflow_service_task_idempotency_unique UNIQUE (tenant_id, idempotency_key)
);

COMMENT ON TABLE workflow_service_task IS
    '服务任务：idempotency_key 唯一保证重试不产生第二条；补偿任务用 compensation_of 指向原任务';

CREATE INDEX workflow_service_task_due_idx
    ON workflow_service_task (tenant_id, status, next_attempt_at);
CREATE INDEX workflow_service_task_instance_idx
    ON workflow_service_task (tenant_id, instance_id);

ALTER TABLE workflow_service_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_service_task FORCE ROW LEVEL SECURITY;

CREATE POLICY workflow_service_task_isolation ON workflow_service_task
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE workflow_exception (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    instance_id uuid        REFERENCES workflow_instance (id) ON DELETE CASCADE,
    task_id     uuid        REFERENCES workflow_task (id) ON DELETE SET NULL,
    code        text        NOT NULL,
    message     text        NOT NULL,
    payload     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status      text        NOT NULL DEFAULT 'open',
    resolved_by uuid,
    resolved_at timestamptz,
    resolution  text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflow_exception_code_check CHECK (code IN (
        'ASSIGNEE_UNRESOLVED', 'SLA_BREACHED', 'SERVICE_FAILED',
        'CONDITION_NO_MATCH', 'HANDLER_MISSING', 'BUSINESS_ACTION_FAILED')),
    CONSTRAINT workflow_exception_status_check
        CHECK (status IN ('open', 'resolved')),
    CONSTRAINT workflow_exception_payload_check CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT workflow_exception_resolved_check CHECK (
        status = 'open' OR (resolved_at IS NOT NULL AND resolved_by IS NOT NULL))
);

COMMENT ON TABLE workflow_exception IS
    '异常队列：无审批人、SLA 超时、服务/业务失败等；任何异常都不会自动批准流程';

CREATE INDEX workflow_exception_open_idx
    ON workflow_exception (tenant_id, status, created_at DESC);

ALTER TABLE workflow_exception ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_exception FORCE ROW LEVEL SECURITY;

CREATE POLICY workflow_exception_isolation ON workflow_exception
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
