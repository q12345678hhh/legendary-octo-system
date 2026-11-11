-- V1 基线：租户最小模型与事务 outbox。
--
-- 规则（WP01-03）：迁移只能前向追加，已进入共享环境的文件不得修改；
-- 版本号全局唯一，按全序执行，模块不得自建迁移目录。
--
-- 本版不含 RLS 策略：RLS 需要先有租户上下文变量与连接池复用测试，
-- 随 WP01-03 的独立迁移追加。表名按模块前缀区分，便于「禁止跨模块读内部表」的检查。

CREATE TABLE tenancy_tenant (
    id         uuid PRIMARY KEY,
    slug       text        NOT NULL,
    name       text        NOT NULL,
    status     text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenancy_tenant_slug_key UNIQUE (slug),
    CONSTRAINT tenancy_tenant_status_check CHECK (status IN ('active', 'frozen', 'closed'))
);

COMMENT ON TABLE tenancy_tenant IS '租户主表：id 即 tenant_id，其余业务表以它作为隔离键';
COMMENT ON COLUMN tenancy_tenant.status IS '生产停用不等同删除：frozen 仍可受控导出与申诉';

CREATE TABLE platform_outbox (
    event_id       uuid PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    event_name     text        NOT NULL,
    schema_version integer     NOT NULL,
    producer       text        NOT NULL,
    correlation_id text        NOT NULL,
    trace_id       text,
    occurred_at    timestamptz NOT NULL,
    payload        jsonb       NOT NULL,
    status         text        NOT NULL DEFAULT 'pending',
    attempts       integer     NOT NULL DEFAULT 0,
    available_at   timestamptz NOT NULL DEFAULT now(),
    last_error     text,
    published_at   timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_outbox_status_check
        CHECK (status IN ('pending', 'processing', 'published', 'failed', 'dead'))
);

COMMENT ON TABLE platform_outbox IS '事务 outbox：与业务写入同事务提交，由 Worker 抢占投递';
COMMENT ON COLUMN platform_outbox.correlation_id IS '对应事件信封的 correlation_id，跨进程关联用';
COMMENT ON COLUMN platform_outbox.trace_id IS '写入时链路的 trace_id，用于把页面、API、Worker 串起来';
COMMENT ON COLUMN platform_outbox.available_at IS '下一次可投递时间，失败重试时后移而不是立即重试';

-- 投递扫描只关心待投递与可重试失败，避免全表扫描。
CREATE INDEX platform_outbox_dispatch_idx
    ON platform_outbox (available_at)
    WHERE status IN ('pending', 'failed');

CREATE INDEX platform_outbox_tenant_idx
    ON platform_outbox (tenant_id, occurred_at DESC);
