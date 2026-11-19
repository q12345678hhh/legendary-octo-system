-- V23：WP04-05 隐私、保留与删除。
--
-- 设计要点：
--   1. 语音记录与单据进入统一回收站：只加 recycled_at 标记，业务查询一律过滤；
--   2. 已入账单据属于会计档案，不允许普通删除，只能匿名化 OCR 文本；
--   3. 匿名化是唯一被允许改写抽取记录的场景，由 app.privacy_anonymize 开关控制，
--      默认仍拒绝；
--   4. 租户隐私设置按租户一行，缺省值由服务层回退，数据库中仍校验范围；
--   5. 新表启用并强制 RLS。

-- ------------------------------------------------------------------ 回收标记

ALTER TABLE worklog_voice_capture ADD COLUMN recycled_at timestamptz;
ALTER TABLE expense_document ADD COLUMN recycled_at timestamptz;

CREATE INDEX worklog_voice_capture_active_idx
    ON worklog_voice_capture (tenant_id, person_id, created_at DESC)
    WHERE recycled_at IS NULL;
CREATE INDEX expense_document_active_idx
    ON expense_document (tenant_id, uploader_person_id, created_at DESC)
    WHERE recycled_at IS NULL;

-- ------------------------------------------------------------------ 数据类白名单

ALTER TABLE retention_bin_item DROP CONSTRAINT retention_bin_item_class_check;
ALTER TABLE retention_bin_item
    ADD CONSTRAINT retention_bin_item_class_check
        CHECK (data_class IN ('file', 'worklog_entry', 'worklog_voice_capture',
                              'expense_document'));

ALTER TABLE retention_legal_hold DROP CONSTRAINT retention_legal_hold_class_check;
ALTER TABLE retention_legal_hold
    ADD CONSTRAINT retention_legal_hold_class_check
        CHECK (data_class IN ('file', 'worklog_entry', 'worklog_weekly_report',
                              'worklog_voice_capture', 'expense_document',
                              'person', 'payroll_result', 'audit'));

-- ------------------------------------------------------------------ 租户隐私设置

CREATE TABLE tenant_privacy_setting (
    tenant_id                    uuid PRIMARY KEY REFERENCES tenancy_tenant (id),
    data_residency               text        NOT NULL DEFAULT 'cn',
    training_opt_out             boolean     NOT NULL DEFAULT true,
    voice_transcript_retention_days integer NOT NULL DEFAULT 365,
    expense_ocr_retention_days   integer     NOT NULL DEFAULT 3650,
    updated_by                   text,
    updated_at                   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_privacy_residency_check
        CHECK (data_residency IN ('cn', 'eu', 'us', 'sg')),
    CONSTRAINT tenant_privacy_voice_days_check
        CHECK (voice_transcript_retention_days BETWEEN 1 AND 3650),
    CONSTRAINT tenant_privacy_ocr_days_check
        CHECK (expense_ocr_retention_days BETWEEN 1 AND 3650)
);

COMMENT ON TABLE tenant_privacy_setting IS
    '租户隐私设置：数据驻留地、供应商训练开关与语音/OCR 保留天数；缺行视为默认值';
COMMENT ON COLUMN tenant_privacy_setting.training_opt_out IS
    'true=明确要求供应商不将租户数据用于模型训练（默认）';
COMMENT ON COLUMN tenant_privacy_setting.voice_transcript_retention_days IS
    '语音转写文本保留天数，音频仍在确认后立即删除';
COMMENT ON COLUMN tenant_privacy_setting.expense_ocr_retention_days IS
    '单据 OCR 文本保留天数，已入账单据只能匿名化不能删除';

ALTER TABLE tenant_privacy_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_privacy_setting FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_privacy_setting_isolation ON tenant_privacy_setting
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 匿名化开关

-- 抽取记录默认只允许插入；数据主体删除中被判定为「只能匿名化」的已入账单据
-- 需要在同一事务内清空 ocr_text 与 fields，因此仅当开关为 on 时放行改删。
CREATE OR REPLACE FUNCTION expense_document_extraction_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('app.privacy_anonymize', true) = 'on' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    RAISE EXCEPTION '扩展抽取记录只允许插入，不允许修改或删除';
END
$$;

-- 回收站物理清理需要连带删除复核记录；审计不删除，只在开关下把 document_id 置空，
-- 保留「谁在何时做了什么」的链，避免已删除单据复活。
CREATE OR REPLACE FUNCTION expense_document_review_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('app.privacy_anonymize', true) = 'on' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    RAISE EXCEPTION '复核记录只允许插入，不允许修改或删除';
END
$$;

CREATE OR REPLACE FUNCTION expense_audit_event_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('app.privacy_anonymize', true) = 'on' AND TG_OP = 'UPDATE' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'expense_audit_event 只允许插入，不允许修改或删除';
END
$$;
