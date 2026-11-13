-- V14 工作访问留痕：员工可核对「谁看过我的已提交工作」。
--
-- 只追加、不改不删；记录查看者、被查看者、资源与动作，以及当时的授权因子摘要。
-- 不记录任何工作正文、文件名或 IP 之外的可识别信息。

CREATE TABLE worklog_access_log (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenancy_tenant (id),
    viewer_person_id  uuid        NOT NULL,
    subject_person_id uuid        NOT NULL,
    resource_type     text        NOT NULL,
    resource_id       text,
    action            text        NOT NULL,
    decision_factors  jsonb       NOT NULL DEFAULT '[]'::jsonb,
    occurred_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT worklog_access_log_action_check CHECK (
        action IN ('view', 'preview', 'download', 'export', 'insight_drilldown')),
    CONSTRAINT worklog_access_log_resource_check CHECK (
        resource_type IN ('work_page', 'worklog_day', 'weekly_report', 'work_file', 'insight')),
    CONSTRAINT worklog_access_log_self_check CHECK (viewer_person_id <> subject_person_id)
);

COMMENT ON TABLE worklog_access_log IS
    '工作域访问留痕：仅追加；self 访问不记录，避免噪音淹没他人访问';
COMMENT ON COLUMN worklog_access_log.decision_factors IS
    '授权因子摘要（授权来源、范围类型），不含正文与个人敏感字段';

CREATE INDEX worklog_access_log_subject_idx
    ON worklog_access_log (tenant_id, subject_person_id, occurred_at DESC);
CREATE INDEX worklog_access_log_viewer_idx
    ON worklog_access_log (tenant_id, viewer_person_id, occurred_at DESC);

ALTER TABLE worklog_access_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklog_access_log FORCE ROW LEVEL SECURITY;

CREATE POLICY worklog_access_log_isolation ON worklog_access_log
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION worklog_access_log_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'worklog_access_log 只允许追加，禁止 % 操作', TG_OP;
END $$;

CREATE TRIGGER worklog_access_log_no_update
    BEFORE UPDATE OR DELETE ON worklog_access_log
    FOR EACH ROW EXECUTE FUNCTION worklog_access_log_append_only();
