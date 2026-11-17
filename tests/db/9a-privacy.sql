-- WP04-05：隐私、保留与删除数据库断言（运行期账号 platform_app）。
--
-- 重点验证：
--   1. 租户隐私设置表启用并强制 RLS，默认值与天数范围由数据库兜底；
--   2. 语音记录与单据可进入统一回收站，数据类白名单只接受已登记类型；
--   3. 抽取、复核记录默认拒绝改删，只有 app.privacy_anonymize=on 时放行；
--   4. 跨租户不可见设置、语音与单据。

DO $$
BEGIN
    IF current_setting('is_superuser') <> 'off' THEN
        RAISE EXCEPTION '用例身份错误：platform_app 不能是超级用户';
    END IF;
    IF current_setting('row_security') <> 'on' THEN
        RAISE EXCEPTION 'row_security 必须为 on';
    END IF;
END
$$;

\set pv_a '''cccccccc-0000-4000-8000-0000000000e1'''
\set pv_b '''dddddddd-0000-4000-8000-0000000000e1'''
\set pv_file '''cccccccc-0000-4000-8000-0000000000f1'''
\set pv_doc '''cccccccc-0000-4000-8000-0000000000d1'''
\set pv_voice '''cccccccc-0000-4000-8000-0000000000c1'''

SELECT set_config('app.tenant_id', :pv_a, false);

BEGIN;
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:pv_a, 'privacy-a', '隐私租户甲', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;
COMMIT;

-- tenancy_tenant 的 RLS 要求行 id 与当前租户一致：切换上下文后建乙租户
SELECT set_config('app.tenant_id', :pv_b, false);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:pv_b, 'privacy-b', '隐私租户乙', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;
SELECT set_config('app.tenant_id', :pv_a, false);

BEGIN;
DELETE FROM retention_bin_item WHERE tenant_id IN (:pv_a, :pv_b);
DELETE FROM retention_legal_hold WHERE tenant_id IN (:pv_a, :pv_b);
-- 清理上次运行留下的抽取/复核记录需要临时打开匿名化开关，否则只插入触发器会拦截
SELECT set_config('app.privacy_anonymize', 'on', false);
DELETE FROM expense_document_review WHERE tenant_id IN (:pv_a, :pv_b);
DELETE FROM expense_document_extraction WHERE tenant_id IN (:pv_a, :pv_b);
SELECT set_config('app.privacy_anonymize', 'off', false);
DELETE FROM expense_invoice_claim WHERE tenant_id IN (:pv_a, :pv_b);
DELETE FROM expense_document WHERE tenant_id IN (:pv_a, :pv_b);
DELETE FROM worklog_voice_capture WHERE tenant_id IN (:pv_a, :pv_b);
DELETE FROM tenant_privacy_setting WHERE tenant_id IN (:pv_a, :pv_b);
DELETE FROM platform_file WHERE id = :pv_file;

INSERT INTO platform_file (id, tenant_id, subject_id, file_name, content_type, size_bytes,
                           object_key, status, scan_status)
VALUES (:pv_file, :pv_a, 'cccccccc-0000-4000-8000-0000000000a1', 'privacy.png', 'image/png',
        1024, 'tenant-a/privacy/privacy.png', 'available', 'clean');

INSERT INTO worklog_voice_capture
    (id, tenant_id, person_id, work_date, object_key, content_type, size_bytes,
     status, client_request_id)
VALUES (:pv_voice, :pv_a, 'cccccccc-0000-4000-8000-0000000000a1', current_date,
        'tenant-a/voice/one', 'audio/webm', 2048, 'transcribed', 'privacy-voice-1');

INSERT INTO expense_document
    (id, tenant_id, uploader_person_id, file_id, doc_type, status, currency, amount,
     rule_version, policy_version, posted_at, post_actor_kind)
VALUES (:pv_doc, :pv_a, 'cccccccc-0000-4000-8000-0000000000a1', :pv_file, 'invoice',
        'posted', 'CNY', 88.00, 'expense-rules-2026.11', 1, now(), 'ai');
COMMIT;

-- 1. 隐私设置默认值与范围
BEGIN;
SELECT set_config('app.tenant_id', :pv_a, true);
INSERT INTO tenant_privacy_setting (tenant_id) VALUES (:pv_a);
DO $$
DECLARE
    setting record;
    blocked boolean;
BEGIN
    SELECT data_residency, training_opt_out, voice_transcript_retention_days,
           expense_ocr_retention_days
      INTO setting FROM tenant_privacy_setting;
    IF setting.data_residency <> 'cn' OR setting.training_opt_out IS NOT TRUE
       OR setting.voice_transcript_retention_days <> 365
       OR setting.expense_ocr_retention_days <> 3650 THEN
        RAISE EXCEPTION '隐私设置默认值不符合预期：%', setting;
    END IF;

    blocked := false;
    BEGIN
        UPDATE tenant_privacy_setting SET voice_transcript_retention_days = 0;
        RAISE EXCEPTION '语音保留天数 0 应被范围约束拒绝';
    EXCEPTION WHEN check_violation THEN blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '语音保留天数范围约束未生效';
    END IF;
END
$$;
COMMIT;

-- 2. 新数据类可进回收站与法定留置
BEGIN;
SELECT set_config('app.tenant_id', :pv_a, true);
INSERT INTO retention_bin_item
    (tenant_id, data_class, object_id, object_ref, snapshot, deleted_by, purge_after)
VALUES (:pv_a, 'worklog_voice_capture', :pv_voice, 'tenant-a/voice/one', '{}'::jsonb,
        'tester', now() + interval '30 days'),
       (:pv_a, 'expense_document', :pv_doc, 'expense/privacy', '{}'::jsonb,
        'tester', now() + interval '30 days');
INSERT INTO retention_legal_hold (tenant_id, data_class, object_id, reason, created_by)
VALUES (:pv_a, 'worklog_voice_capture', :pv_voice, '监管核查需要保留语音记录', 'tester'),
       (:pv_a, 'expense_document', :pv_doc, '监管核查需要保留单据记录', 'tester');
DO $$
DECLARE
    blocked boolean;
BEGIN
    blocked := false;
    BEGIN
        INSERT INTO retention_bin_item
            (tenant_id, data_class, object_id, snapshot, deleted_by, purge_after)
        VALUES ('cccccccc-0000-4000-8000-0000000000e1', 'person',
                'cccccccc-0000-4000-8000-0000000000a1', '{}'::jsonb, 'tester',
                now() + interval '30 days');
        RAISE EXCEPTION '未登记数据类不应允许进入回收站';
    EXCEPTION WHEN check_violation THEN blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '回收站数据类白名单未生效';
    END IF;
END
$$;
UPDATE worklog_voice_capture SET recycled_at = now() WHERE id = :pv_voice;
UPDATE expense_document SET recycled_at = now() WHERE id = :pv_doc;
COMMIT;

-- 3. 抽取记录默认只插入，匿名化开关只放行开关打开期间
BEGIN;
SELECT set_config('app.tenant_id', :pv_a, true);
INSERT INTO expense_document_extraction
    (tenant_id, document_id, status, ocr_text, fields)
VALUES (:pv_a, :pv_doc, 'ok', '发票号码 12345678', '{"amount":88.00}'::jsonb);

DO $$
DECLARE
    blocked boolean;
BEGIN
    blocked := false;
    BEGIN
        UPDATE expense_document_extraction SET ocr_text = NULL;
        RAISE EXCEPTION '默认必须拒绝改写抽取记录';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM NOT LIKE '%只允许插入%' THEN RAISE; END IF;
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '抽取记录只插入触发器未生效';
    END IF;

    blocked := false;
    BEGIN
        DELETE FROM expense_document_extraction;
        RAISE EXCEPTION '默认必须拒绝删除抽取记录';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM NOT LIKE '%只允许插入%' THEN RAISE; END IF;
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '抽取记录删除未被拦截';
    END IF;
END
$$;

SET LOCAL app.privacy_anonymize = 'on';
UPDATE expense_document_extraction SET ocr_text = NULL, fields = '{}'::jsonb;
DO $$
DECLARE
    remaining text;
BEGIN
    SELECT ocr_text INTO remaining FROM expense_document_extraction;
    IF remaining IS NOT NULL THEN
        RAISE EXCEPTION '匿名化后 OCR 原文应为空，实际 %', remaining;
    END IF;
END
$$;
SET LOCAL app.privacy_anonymize = 'off';

DO $$
DECLARE
    blocked boolean;
BEGIN
    blocked := false;
    BEGIN
        UPDATE expense_document_extraction SET ocr_text = '复活';
        RAISE EXCEPTION '关闭开关后必须重新拒绝改写';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM NOT LIKE '%只允许插入%' THEN RAISE; END IF;
        blocked := true;
    END;
    IF NOT blocked THEN
        RAISE EXCEPTION '匿名化开关未在语句后关闭';
    END IF;
END
$$;
COMMIT;

-- 4. 跨租户隔离
BEGIN;
SELECT set_config('app.tenant_id', :pv_b, true);
DO $$
DECLARE
    settings integer;
    voices integer;
    documents integer;
    bin_items integer;
    holds integer;
BEGIN
    SELECT count(*) INTO settings FROM tenant_privacy_setting;
    SELECT count(*) INTO voices FROM worklog_voice_capture WHERE id =
        'cccccccc-0000-4000-8000-0000000000c1';
    SELECT count(*) INTO documents FROM expense_document WHERE id =
        'cccccccc-0000-4000-8000-0000000000d1';
    SELECT count(*) INTO bin_items FROM retention_bin_item;
    SELECT count(*) INTO holds FROM retention_legal_hold;
    IF settings <> 0 OR voices <> 0 OR documents <> 0 OR bin_items <> 0 OR holds <> 0 THEN
        RAISE EXCEPTION '跨租户可见：settings=% voices=% documents=% bin=% holds=%',
            settings, voices, documents, bin_items, holds;
    END IF;
END
$$;
COMMIT;

DO $$
BEGIN
    RAISE NOTICE 'WP04-05 隐私保留与删除数据库断言通过';
END
$$;
