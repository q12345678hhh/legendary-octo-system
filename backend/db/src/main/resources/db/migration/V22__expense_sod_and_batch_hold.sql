-- V22：WP04-04 职责分离与批量异常处置。
--
-- 设计要点：
--   1. 批量异常按「同一供应商 / 同一上传人 / 金额集中」三类范围记录暂停；
--   2. 同一租户、同一范围只允许一条 active 记录，释放后可再次触发；
--   3. 记录只允许释放，不允许删除，保证异常处置过程可追溯；
--   4. 启用并强制 RLS；创建人、释放人一律由服务端从当前会话解析。

CREATE TABLE expense_batch_hold (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenancy_tenant (id),
    scope_type     text        NOT NULL,
    scope_key      text        NOT NULL,
    reason         text        NOT NULL,
    window_hours   integer     NOT NULL DEFAULT 24,
    document_count integer     NOT NULL,
    amount_total   numeric(18, 2),
    status         text        NOT NULL DEFAULT 'active',
    created_by     uuid,
    created_at     timestamptz NOT NULL DEFAULT now(),
    released_by    uuid,
    released_at    timestamptz,
    release_note   text,
    CONSTRAINT expense_batch_hold_scope_check
        CHECK (scope_type IN ('vendor', 'uploader', 'amount')),
    CONSTRAINT expense_batch_hold_key_check CHECK (length(btrim(scope_key)) > 0),
    CONSTRAINT expense_batch_hold_reason_check CHECK (length(btrim(reason)) > 0),
    CONSTRAINT expense_batch_hold_window_check CHECK (window_hours BETWEEN 1 AND 720),
    CONSTRAINT expense_batch_hold_count_check CHECK (document_count > 0),
    CONSTRAINT expense_batch_hold_status_check CHECK (status IN ('active', 'released')),
    CONSTRAINT expense_batch_hold_release_check CHECK (
        (status = 'released'
            AND released_at IS NOT NULL AND released_by IS NOT NULL
            AND release_note IS NOT NULL AND length(btrim(release_note)) > 0)
        OR (status = 'active'
            AND released_at IS NULL AND released_by IS NULL AND release_note IS NULL))
);

COMMENT ON TABLE expense_batch_hold IS
    '批量异常暂停：同一供应商/上传人/集中金额命中阈值后暂停该批次自动入账，需人工解除';
COMMENT ON COLUMN expense_batch_hold.scope_key IS
    'vendor=供应商名小写去空格；uploader=员工 UUID；amount=amount:两位小数';
COMMENT ON COLUMN expense_batch_hold.status IS
    'active=暂停自动入账中；released=已人工解除';

CREATE UNIQUE INDEX expense_batch_hold_active_unique
    ON expense_batch_hold (tenant_id, scope_type, scope_key)
    WHERE status = 'active';
CREATE INDEX expense_batch_hold_status_idx
    ON expense_batch_hold (tenant_id, status, created_at DESC);

ALTER TABLE expense_batch_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_batch_hold FORCE ROW LEVEL SECURITY;

CREATE POLICY expense_batch_hold_isolation ON expense_batch_hold
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION expense_batch_hold_no_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '批量异常暂停记录不允许删除，只能释放';
END
$$;

CREATE TRIGGER expense_batch_hold_no_delete_trigger
    BEFORE DELETE ON expense_batch_hold
    FOR EACH ROW EXECUTE FUNCTION expense_batch_hold_no_delete();
