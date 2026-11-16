-- V21：WP04-03 单据上传与 AI 预审。
--
-- 设计要点：
--   1. 单据只引用平台文件，字节仍由对象存储保存；抽取结果、复核与审计各自独立成表；
--   2. 抽取、复核、审计三类记录只允许插入，禁止改写，保证「AI 结论与人工推翻」都可追溯；
--   3. 发票号占用放在 expense_invoice_claim，唯一索引只约束未释放的记录，
--      驳回或撤销后释放，允许重新提交；
--   4. 所有表启用并强制 RLS；提交人、复核人、入账人一律由服务端从当前会话解析。

-- ------------------------------------------------------------------ 单据主体

CREATE TABLE expense_document (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid        NOT NULL REFERENCES tenancy_tenant (id),
    uploader_person_id  uuid        NOT NULL,
    file_id             uuid        NOT NULL REFERENCES platform_file (id),
    doc_type            text        NOT NULL,
    status              text        NOT NULL DEFAULT 'manual_review',
    currency            text,
    amount              numeric(18, 2),
    invoice_code        text,
    invoice_no          text,
    vendor_name         text,
    vendor_tax_no       text,
    occurred_on         date,
    category            text,
    findings            jsonb       NOT NULL DEFAULT '[]'::jsonb,
    confidence          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    rule_version        text        NOT NULL,
    policy_version      integer     NOT NULL DEFAULT 1,
    extraction_id       uuid,
    posted_at           timestamptz,
    posted_by           uuid,
    post_actor_kind     text,
    voided_at           timestamptz,
    voided_by           uuid,
    void_reason         text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT expense_document_type_check CHECK (doc_type IN ('invoice', 'receipt', 'bill')),
    CONSTRAINT expense_document_status_check
        CHECK (status IN ('manual_review', 'approved', 'posted', 'rejected', 'voided')),
    CONSTRAINT expense_document_amount_check CHECK (amount IS NULL OR amount > 0),
    CONSTRAINT expense_document_post_check CHECK (
        status <> 'posted'
        OR (posted_at IS NOT NULL AND post_actor_kind IS NOT NULL
            AND (post_actor_kind = 'ai' OR posted_by IS NOT NULL))),
    CONSTRAINT expense_document_void_check CHECK (
        status <> 'voided'
        OR (voided_at IS NOT NULL AND voided_by IS NOT NULL
            AND void_reason IS NOT NULL AND length(btrim(void_reason)) > 0)),
    CONSTRAINT expense_document_post_actor_check
        CHECK (post_actor_kind IS NULL OR post_actor_kind IN ('ai', 'employee'))
);

COMMENT ON TABLE expense_document IS
    '报销单/发票/账单：AI 预审结论与人工复核结论并存，入账只记录入账事实，不生成借贷凭证';
COMMENT ON COLUMN expense_document.post_actor_kind IS
    'ai=全规则自动通过；employee=人工复核后由入账权限执行';
COMMENT ON COLUMN expense_document.findings IS
    '最近一次预审的逐条规则结果快照（code/status/message/actual/expected/confidence）';

CREATE INDEX expense_document_status_idx
    ON expense_document (tenant_id, status, created_at DESC);
CREATE INDEX expense_document_uploader_idx
    ON expense_document (tenant_id, uploader_person_id, created_at DESC);
CREATE INDEX expense_document_invoice_idx
    ON expense_document (tenant_id, invoice_code, invoice_no)
    WHERE invoice_no IS NOT NULL;

ALTER TABLE expense_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_document FORCE ROW LEVEL SECURITY;

CREATE POLICY expense_document_isolation ON expense_document
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 抽取结果（只插入）

CREATE TABLE expense_document_extraction (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenancy_tenant (id),
    document_id  uuid        NOT NULL REFERENCES expense_document (id),
    provider     text,
    model        text,
    status       text        NOT NULL,
    ocr_text     text,
    fields       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    confidence   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    latency_ms   integer,
    error_code   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT expense_extraction_status_check
        CHECK (status IN ('ok', 'unavailable', 'timeout', 'invalid', 'pdf_unsupported', 'manual')),
    CONSTRAINT expense_extraction_text_check CHECK (ocr_text IS NULL OR length(ocr_text) <= 20000)
);

COMMENT ON TABLE expense_document_extraction IS
    'OCR/人工字段抽取原文：只插入不可改，低置信度与失败状态一律转人工';

CREATE INDEX expense_extraction_document_idx
    ON expense_document_extraction (tenant_id, document_id, created_at DESC);

ALTER TABLE expense_document_extraction ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_document_extraction FORCE ROW LEVEL SECURITY;

CREATE POLICY expense_extraction_isolation ON expense_document_extraction
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION expense_document_extraction_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '扩展抽取记录只允许插入，不允许修改或删除';
END
$$;

CREATE TRIGGER expense_document_extraction_immutable_trigger
    BEFORE UPDATE OR DELETE ON expense_document_extraction
    FOR EACH ROW EXECUTE FUNCTION expense_document_extraction_immutable();

-- ------------------------------------------------------------------ 复核记录（只插入）

CREATE TABLE expense_document_review (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    document_id      uuid        NOT NULL REFERENCES expense_document (id),
    decision         text        NOT NULL,
    comment          text        NOT NULL DEFAULT '',
    overrides        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    actor_person_id  uuid        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT expense_review_decision_check CHECK (decision IN ('approve', 'reject'))
);

COMMENT ON TABLE expense_document_review IS
    '人工复核结论：推翻 AI 时只追加本表，不改抽取与预审快照';

CREATE INDEX expense_review_document_idx
    ON expense_document_review (tenant_id, document_id, created_at DESC);

ALTER TABLE expense_document_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_document_review FORCE ROW LEVEL SECURITY;

CREATE POLICY expense_review_isolation ON expense_document_review
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION expense_document_review_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '复核记录只允许插入，不允许修改或删除';
END
$$;

CREATE TRIGGER expense_document_review_immutable_trigger
    BEFORE UPDATE OR DELETE ON expense_document_review
    FOR EACH ROW EXECUTE FUNCTION expense_document_review_immutable();

-- ------------------------------------------------------------------ 发票号占用

CREATE TABLE expense_invoice_claim (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    document_id   uuid        NOT NULL REFERENCES expense_document (id),
    invoice_code  text        NOT NULL DEFAULT '',
    invoice_no    text        NOT NULL,
    claimed_at    timestamptz NOT NULL DEFAULT now(),
    released_at   timestamptz,
    release_reason text
);

COMMENT ON TABLE expense_invoice_claim IS
    '发票号占用：未释放记录唯一；驳回/撤销后释放，允许重新报销';

CREATE UNIQUE INDEX expense_invoice_claim_active_unique
    ON expense_invoice_claim (tenant_id, invoice_code, invoice_no)
    WHERE released_at IS NULL;
CREATE INDEX expense_invoice_claim_document_idx
    ON expense_invoice_claim (tenant_id, document_id);

ALTER TABLE expense_invoice_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_invoice_claim FORCE ROW LEVEL SECURITY;

CREATE POLICY expense_invoice_claim_isolation ON expense_invoice_claim
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 租户策略

CREATE TABLE expense_tenant_policy (
    tenant_id          uuid PRIMARY KEY REFERENCES tenancy_tenant (id),
    auto_post_enabled  boolean       NOT NULL DEFAULT true,
    auto_post_limit    numeric(18, 2) NOT NULL DEFAULT 5000,
    window_days        integer       NOT NULL DEFAULT 90,
    legal_title        text,
    tax_id             text,
    vendor_allowlist   jsonb         NOT NULL DEFAULT '[]'::jsonb,
    manual_categories  jsonb         NOT NULL DEFAULT '[]'::jsonb,
    allowed_categories jsonb         NOT NULL DEFAULT '[]'::jsonb,
    min_confidence     numeric(3, 2) NOT NULL DEFAULT 0.70,
    verify_required    boolean       NOT NULL DEFAULT true,
    version            integer       NOT NULL DEFAULT 1,
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT expense_policy_limit_check CHECK (auto_post_limit >= 0),
    CONSTRAINT expense_policy_window_check CHECK (window_days BETWEEN 1 AND 3650),
    CONSTRAINT expense_policy_confidence_check CHECK (min_confidence BETWEEN 0 AND 1),
    CONSTRAINT expense_policy_version_check CHECK (version >= 1)
);

COMMENT ON TABLE expense_tenant_policy IS
    '租户预审策略：金额阈值、白名单、人工科目与验真开关；变更需 manage 权限并写审计';

ALTER TABLE expense_tenant_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_tenant_policy FORCE ROW LEVEL SECURITY;

CREATE POLICY expense_tenant_policy_isolation ON expense_tenant_policy
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 审计（只插入）

CREATE TABLE expense_audit_event (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    document_id      uuid        REFERENCES expense_document (id),
    action           text        NOT NULL,
    actor_person_id  uuid,
    actor_kind       text        NOT NULL,
    detail           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT expense_audit_action_check CHECK (length(btrim(action)) > 0),
    CONSTRAINT expense_audit_actor_check
        CHECK (actor_kind IN ('employee', 'reviewer', 'poster', 'ai', 'system'))
);

COMMENT ON TABLE expense_audit_event IS
    '单据审计：提交、抽取、预审、复核、入账、撤销与策略变更只追加，不保存 OCR 原文';

CREATE INDEX expense_audit_document_idx
    ON expense_audit_event (tenant_id, document_id, occurred_at);

ALTER TABLE expense_audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_audit_event FORCE ROW LEVEL SECURITY;

CREATE POLICY expense_audit_isolation ON expense_audit_event
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION expense_audit_event_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'expense_audit_event 只允许插入，不允许修改或删除';
END
$$;

CREATE TRIGGER expense_audit_event_immutable_trigger
    BEFORE UPDATE OR DELETE ON expense_audit_event
    FOR EACH ROW EXECUTE FUNCTION expense_audit_event_immutable();

-- ------------------------------------------------------------------ 权限码

INSERT INTO authorization_permission (code, resource_type, action, description, sensitive) VALUES
    ('expense.document.read', 'expense.document', 'read', '查看本人或授权范围内的单据', true),
    ('expense.document.submit', 'expense.document', 'submit', '提交报销单/发票/账单', false),
    ('expense.document.review', 'expense.document', 'review', '人工复核单据预审结论', true),
    ('expense.document.post', 'expense.document', 'post', '执行单据入账', true),
    ('expense.document.void', 'expense.document', 'void', '撤销已入账单据', true),
    ('expense.policy.manage', 'expense.policy', 'manage', '维护租户预审策略', true);
