-- V4 通用平台技术骨架：幂等、异步作业、文件与死信回放审计。
--
-- 三张业务表照抄 backend/db/templates/tenant-table.template.sql：
-- ENABLE + FORCE RLS、nullif(current_setting('app.tenant_id', true), '') 策略。
-- 应用侧必须把租户上下文与业务写库放在同一事务里（TenantTransactions），
-- 否则 INSERT 会撞上 WITH CHECK 而失败——这是预期行为，不是配置错误。
--
-- platform_outbox 仍不开启 RLS，原因写在表注释里：Worker 的职责就是跨租户抢占投递，
-- 给它一个租户上下文会让「别的租户的事件永远发不出去」。代价是 API 侧所有
-- outbox 查询必须显式带 tenant_id 条件，由 DeadLetterStore 负责，不允许业务模块直读。

CREATE TABLE platform_idempotency (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    subject_id      text        NOT NULL,
    endpoint        text        NOT NULL,
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    status          text        NOT NULL DEFAULT 'in_progress',
    response_status integer,
    response_content_type text,
    response_body    jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    completed_at    timestamptz,
    expires_at      timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    CONSTRAINT platform_idempotency_status_check CHECK (status IN ('in_progress', 'completed')),
    CONSTRAINT platform_idempotency_key_unique UNIQUE (tenant_id, subject_id, endpoint, idempotency_key)
);

COMMENT ON TABLE platform_idempotency IS
    '写请求幂等记录：同一主体在同一端点重复提交同一个幂等键时重放首次响应，不再产生副作用';
COMMENT ON COLUMN platform_idempotency.request_hash IS
    '方法+路径+请求体的 SHA-256；幂等键复用但内容不同时必须拒绝，不能把旧响应当成新请求的结果';

CREATE INDEX platform_idempotency_tenant_idx ON platform_idempotency (tenant_id, created_at DESC);
CREATE INDEX platform_idempotency_expiry_idx ON platform_idempotency (expires_at);

ALTER TABLE platform_idempotency ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_idempotency FORCE ROW LEVEL SECURITY;

CREATE POLICY platform_idempotency_isolation ON platform_idempotency
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE platform_job (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    subject_id       text        NOT NULL,
    job_type         text        NOT NULL,
    status           text        NOT NULL DEFAULT 'queued',
    progress         integer     NOT NULL DEFAULT 0,
    cancel_requested boolean     NOT NULL DEFAULT false,
    payload          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    result           jsonb,
    last_error       text,
    trace_id         text        NOT NULL,
    correlation_id   text        NOT NULL,
    requested_at     timestamptz NOT NULL DEFAULT now(),
    started_at       timestamptz,
    finished_at      timestamptz,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_job_status_check
        CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    CONSTRAINT platform_job_progress_check CHECK (progress BETWEEN 0 AND 100)
);

COMMENT ON TABLE platform_job IS
    '异步作业：创建与 outbox 事件同事务提交，Worker 消费事件后按租户上下文推进进度';
COMMENT ON COLUMN platform_job.cancel_requested IS
    '取消是协作式的：API 只置标记，Worker 在下一个进度点读取并停止，不会强杀线程';

CREATE INDEX platform_job_tenant_idx ON platform_job (tenant_id, requested_at DESC);
CREATE INDEX platform_job_tenant_status_idx ON platform_job (tenant_id, status);
-- Worker 不需要跨租户扫描：作业只由所属租户的 outbox 事件驱动，避免再引入一个
-- 需要 BYPASSRLS 的角色。

ALTER TABLE platform_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_job FORCE ROW LEVEL SECURITY;

CREATE POLICY platform_job_isolation ON platform_job
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE platform_file (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenancy_tenant (id),
    subject_id   text        NOT NULL,
    file_name    text        NOT NULL,
    content_type text        NOT NULL,
    size_bytes   bigint      NOT NULL,
    sha256       text,
    object_key   text        NOT NULL,
    status       text        NOT NULL DEFAULT 'pending_upload',
    scan_status  text        NOT NULL DEFAULT 'pending',
    scan_detail  text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    uploaded_at  timestamptz,
    scanned_at   timestamptz,
    CONSTRAINT platform_file_status_check
        CHECK (status IN ('pending_upload', 'uploaded', 'available', 'rejected')),
    CONSTRAINT platform_file_scan_status_check
        CHECK (scan_status IN ('pending', 'scanning', 'clean', 'infected', 'failed')),
    CONSTRAINT platform_file_size_check CHECK (size_bytes >= 0),
    CONSTRAINT platform_file_object_key_unique UNIQUE (object_key)
);

COMMENT ON TABLE platform_file IS
    '文件元数据：字节只进对象存储，库里只留键、哈希与扫描状态；下载必须重新授权';
COMMENT ON COLUMN platform_file.object_key IS
    '对象键以 tenant_id/file_id 开头，保证同一存储桶内租户前缀天然隔离';
COMMENT ON COLUMN platform_file.scan_status IS
    '只有 clean 才允许签发下载 URL；infected/failed 必须走人工处置，不允许跳过';

CREATE INDEX platform_file_tenant_idx ON platform_file (tenant_id, created_at DESC);
CREATE INDEX platform_file_tenant_status_idx ON platform_file (tenant_id, scan_status);

ALTER TABLE platform_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_file FORCE ROW LEVEL SECURITY;

CREATE POLICY platform_file_isolation ON platform_file
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE platform_outbox_replay (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id    uuid        NOT NULL REFERENCES platform_outbox (event_id),
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    reason      text        NOT NULL,
    replayed_by text        NOT NULL,
    replayed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_outbox_replay_reason_check CHECK (length(btrim(reason)) > 0)
);

COMMENT ON TABLE platform_outbox_replay IS
    '死信回放审计：每一次人工重投都必须留下原因与操作主体，回放只重投事件，不做业务补偿';

CREATE INDEX platform_outbox_replay_tenant_idx ON platform_outbox_replay (tenant_id, replayed_at DESC);

ALTER TABLE platform_outbox_replay ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_outbox_replay FORCE ROW LEVEL SECURITY;

CREATE POLICY platform_outbox_replay_isolation ON platform_outbox_replay
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

COMMENT ON TABLE platform_outbox IS
    '事务 outbox：故意不启用 RLS，Worker 的投递必须跨租户扫描；'
    '跨租户访问只允许出现在 Worker 的认领语句与平台死信查询（显式 tenant_id 过滤）';
