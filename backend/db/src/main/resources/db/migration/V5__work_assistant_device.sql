-- V5 Windows 工作助手技术骨架：设备身份、租户策略追加项与最小化片段。
--
-- 三张表全部按 backend/db/templates/tenant-table.template.sql 启用 ENABLE + FORCE RLS。
-- 设备私钥永远不下发到服务端：这里只保存设备公钥（X.509 SubjectPublicKeyInfo，base64）。
-- 租户策略只保存「追加排除项」；平台强制排除下限在 contracts/work-assistant/baseline-policy.v1.json，
-- 由服务端在签名前做并集，数据库里不存一份可被删减的下限。
-- 片段表只收最小化元数据（时间范围、来源类别、摘要），不存原始窗口内容、音频或屏幕图像。

CREATE TABLE work_assistant_device (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    subject_id      text        NOT NULL,
    device_name     text        NOT NULL,
    public_key_spki text        NOT NULL,
    app_version     text,
    policy_version  integer     NOT NULL DEFAULT 0,
    status          text        NOT NULL DEFAULT 'active',
    registered_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz,
    revoked_at      timestamptz,
    revoked_by      text,
    CONSTRAINT work_assistant_device_status_check CHECK (status IN ('active', 'revoked')),
    CONSTRAINT work_assistant_device_name_check CHECK (length(btrim(device_name)) BETWEEN 1 AND 200),
    CONSTRAINT work_assistant_device_key_unique UNIQUE (tenant_id, subject_id, public_key_spki)
);

COMMENT ON TABLE work_assistant_device IS
    '工作助手设备登记：只记录身份与策略状态，不保存任何「远程开启采集」的能力或指令字段';
COMMENT ON COLUMN work_assistant_device.public_key_spki IS
    '设备 ECDSA P-256 公钥（SPKI base64）；私钥只存在员工机器的 DPAPI 加密文件中';
COMMENT ON COLUMN work_assistant_device.status IS
    'revoked 后该设备的上传、心跳与策略拉取一律 403，必须先重新注册';

CREATE INDEX work_assistant_device_tenant_idx ON work_assistant_device (tenant_id, registered_at DESC);
CREATE INDEX work_assistant_device_tenant_subject_idx ON work_assistant_device (tenant_id, subject_id);

ALTER TABLE work_assistant_device ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_assistant_device FORCE ROW LEVEL SECURITY;

CREATE POLICY work_assistant_device_isolation ON work_assistant_device
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE work_assistant_policy (
    tenant_id             uuid PRIMARY KEY REFERENCES tenancy_tenant (id),
    version               integer     NOT NULL DEFAULT 1,
    additional_exclusions jsonb       NOT NULL DEFAULT '[]'::jsonb,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    updated_by            text        NOT NULL,
    CONSTRAINT work_assistant_policy_version_check CHECK (version >= 1),
    CONSTRAINT work_assistant_policy_exclusions_check CHECK (jsonb_typeof(additional_exclusions) = 'array')
);

COMMENT ON TABLE work_assistant_policy IS
    '租户级策略追加项：只能追加排除，不能删除平台下限；版本单调递增，旧版本签名不被客户端接受';
COMMENT ON COLUMN work_assistant_policy.additional_exclusions IS
    '进程名数组；服务端校验格式后与基线取并集再签名，数据库中没有「减少排除」的字段';

ALTER TABLE work_assistant_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_assistant_policy FORCE ROW LEVEL SECURITY;

CREATE POLICY work_assistant_policy_isolation ON work_assistant_policy
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE work_assistant_segment (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenancy_tenant (id),
    device_id         uuid        NOT NULL REFERENCES work_assistant_device (id),
    client_segment_id uuid        NOT NULL,
    captured_from     timestamptz NOT NULL,
    captured_to       timestamptz NOT NULL,
    source_type       text        NOT NULL,
    summary           text,
    policy_version    integer     NOT NULL,
    received_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT work_assistant_segment_time_check CHECK (captured_to > captured_from),
    CONSTRAINT work_assistant_segment_source_check
        CHECK (source_type IN ('window', 'manual', 'voice')),
    CONSTRAINT work_assistant_segment_summary_check
        CHECK (summary IS NULL OR length(summary) <= 2000),
    CONSTRAINT work_assistant_segment_client_unique UNIQUE (tenant_id, device_id, client_segment_id)
);

COMMENT ON TABLE work_assistant_segment IS
    '最小化片段元数据：客户端重传由 client_segment_id 唯一键去重，服务端不保存原始正文';
COMMENT ON COLUMN work_assistant_segment.summary IS
    '提交前已在本机脱敏的摘要；本阶段仅用于链路验证，不进入主管视图';

CREATE INDEX work_assistant_segment_tenant_idx ON work_assistant_segment (tenant_id, received_at DESC);
CREATE INDEX work_assistant_segment_device_idx ON work_assistant_segment (tenant_id, device_id, received_at DESC);

ALTER TABLE work_assistant_segment ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_assistant_segment FORCE ROW LEVEL SECURITY;

CREATE POLICY work_assistant_segment_isolation ON work_assistant_segment
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
