-- V26 AI 工具目录（插件平台最小版）。
--
-- 设计要点：
--   1. 工具是租户级资源：同一租户内 code 唯一，跨租户互不可见。
--   2. 只登记元数据与启停状态，不在本表保存任何密钥；调用凭据由后续凭证模块托管。
--   3. rating 为人工评分（1-5），用于后续排序，不做自动计算。

CREATE TABLE ai_tool (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenancy_tenant (id),
    code         text        NOT NULL,
    name         text        NOT NULL,
    description  text        NOT NULL DEFAULT '',
    endpoint_url text        NOT NULL,
    input_schema jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status       text        NOT NULL DEFAULT 'enabled',
    rating       integer,
    created_by   text        NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 1,
    CONSTRAINT ai_tool_tenant_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT ai_tool_code_check CHECK (code ~ '^[a-z][a-z0-9._-]{2,63}$'),
    CONSTRAINT ai_tool_name_check CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT ai_tool_endpoint_check CHECK (endpoint_url ~ '^https?://'),
    CONSTRAINT ai_tool_schema_check CHECK (jsonb_typeof(input_schema) = 'object'),
    CONSTRAINT ai_tool_status_check CHECK (status IN ('enabled', 'disabled')),
    CONSTRAINT ai_tool_rating_check CHECK (rating IS NULL OR rating BETWEEN 1 AND 5)
);

COMMENT ON TABLE ai_tool IS
    'AI 工具目录：租户级工具元数据与启停状态；不保存调用密钥';

CREATE INDEX ai_tool_tenant_status_idx
    ON ai_tool (tenant_id, status, updated_at DESC);

ALTER TABLE ai_tool ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_tool FORCE ROW LEVEL SECURITY;

CREATE POLICY ai_tool_isolation ON ai_tool
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
