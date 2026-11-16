-- WP04-03：单据上传与 AI 预审数据库断言（运行期账号 platform_app）。
--
-- 重点验证：
--   1. 六张新表均受强制 RLS 保护，跨租户不可见；
--   2. 抽取、复核、审计三类记录只允许插入，改删一律被触发器拒绝；
--   3. 发票号占用唯一，释放后可重新占用；
--   4. 策略默认值（自动入账开启、验真开启、阈值 5000、置信度 0.70）；
--   5. 单据状态与入账/撤销字段的一致性约束。

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

\set ex_a '''aaaaaaaa-0000-4000-8000-0000000000e1'''
\set ex_b '''bbbbbbbb-0000-4000-8000-0000000000e1'''
\set ex_file '''aaaaaaaa-0000-4000-8000-0000000000f1'''
\set ex_doc '''aaaaaaaa-0000-4000-8000-0000000000d1'''

SELECT set_config('app.tenant_id', :ex_a, false);

BEGIN;
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:ex_a, 'expense-a', '单据租户甲', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;

DELETE FROM expense_audit_event WHERE tenant_id IN (:ex_a, :ex_b);
DELETE FROM expense_document_review WHERE tenant_id IN (:ex_a, :ex_b);
DELETE FROM expense_document_extraction WHERE tenant_id IN (:ex_a, :ex_b);
DELETE FROM expense_invoice_claim WHERE tenant_id IN (:ex_a, :ex_b);
DELETE FROM expense_document WHERE tenant_id IN (:ex_a, :ex_b);
DELETE FROM expense_tenant_policy WHERE tenant_id IN (:ex_a, :ex_b);
DELETE FROM platform_file WHERE id = :ex_file;

INSERT INTO platform_file (id, tenant_id, subject_id, file_name, content_type, size_bytes,
                           object_key, status, scan_status)
VALUES (:ex_file, :ex_a, 'aaaaaaaa-0000-4000-8000-0000000000a1', 'invoice.png', 'image/png',
        1024, 'tenant-a/expense/invoice.png', 'available', 'clean');

INSERT INTO expense_document
    (id, tenant_id, uploader_person_id, file_id, doc_type, status, currency, amount,
     invoice_code, invoice_no, rule_version, policy_version)
VALUES (:ex_doc, :ex_a, 'aaaaaaaa-0000-4000-8000-0000000000a1', :ex_file, 'invoice',
        'manual_review', 'CNY', 128.50, '044001900111', '88000011', 'expense-rules-2026.11', 1);
COMMIT;

-- 1. 跨租户隔离：乙租户看不到甲的单据与策略
BEGIN;
SELECT set_config('app.tenant_id', :ex_b, true);
DO $$
DECLARE
    docs integer;
    policies integer;
BEGIN
    SELECT count(*) INTO docs FROM expense_document;
    IF docs <> 0 THEN
        RAISE EXCEPTION '跨租户不应看到单据，实际 % 行', docs;
    END IF;
    SELECT count(*) INTO policies FROM expense_tenant_policy;
    IF policies <> 0 THEN
        RAISE EXCEPTION '跨租户不应看到策略，实际 % 行', policies;
    END IF;
    BEGIN
        INSERT INTO expense_document
            (tenant_id, uploader_person_id, file_id, doc_type, rule_version)
        VALUES ('bbbbbbbb-0000-4000-8000-0000000000e1',
                'bbbbbbbb-0000-4000-8000-0000000000a1',
                'aaaaaaaa-0000-4000-8000-0000000000f1', 'invoice', 'expense-rules-2026.11');
        RAISE EXCEPTION '乙租户不能写入甲租户可见的单据';
    EXCEPTION WHEN insufficient_privilege OR foreign_key_violation THEN NULL;
    END;
END
$$;
COMMIT;

-- 2. 只允许插入：抽取、复核、审计改删必须失败
BEGIN;
SELECT set_config('app.tenant_id', :ex_a, true);
INSERT INTO expense_document_extraction
    (tenant_id, document_id, provider, model, status, ocr_text, fields, confidence)
VALUES (:ex_a, :ex_doc, 'openai-compatible', 'ocr-1', 'ok', '发票代码 044001900111 金额 128.50',
        '{"invoiceNo":"88000011","amount":128.50}'::jsonb, '{"amount":0.98}'::jsonb);

INSERT INTO expense_document_review
    (tenant_id, document_id, decision, comment, actor_person_id)
VALUES (:ex_a, :ex_doc, 'approve', '金额与明细一致',
        'aaaaaaaa-0000-4000-8000-0000000000a2');

INSERT INTO expense_audit_event
    (tenant_id, document_id, action, actor_person_id, actor_kind, detail)
VALUES (:ex_a, :ex_doc, 'submitted', 'aaaaaaaa-0000-4000-8000-0000000000a1', 'employee',
        '{"docType":"invoice"}'::jsonb);
COMMIT;

DO $$
BEGIN
    BEGIN
        UPDATE expense_document_extraction SET status = 'invalid';
        RAISE EXCEPTION '抽取记录应不可修改';
    EXCEPTION WHEN raise_exception THEN NULL;
    END;
    BEGIN
        DELETE FROM expense_document_extraction;
        RAISE EXCEPTION '抽取记录应不可删除';
    EXCEPTION WHEN raise_exception THEN NULL;
    END;
    BEGIN
        UPDATE expense_document_review SET comment = '改口';
        RAISE EXCEPTION '复核记录应不可修改';
    EXCEPTION WHEN raise_exception THEN NULL;
    END;
    BEGIN
        DELETE FROM expense_document_review;
        RAISE EXCEPTION '复核记录应不可删除';
    EXCEPTION WHEN raise_exception THEN NULL;
    END;
    BEGIN
        UPDATE expense_audit_event SET action = 'voided';
        RAISE EXCEPTION '审计记录应不可修改';
    EXCEPTION WHEN raise_exception THEN NULL;
    END;
    BEGIN
        DELETE FROM expense_audit_event;
        RAISE EXCEPTION '审计记录应不可删除';
    EXCEPTION WHEN raise_exception THEN NULL;
    END;
END
$$;

-- 3. 发票号占用：活动记录唯一，释放后可重新占用
INSERT INTO expense_invoice_claim (tenant_id, document_id, invoice_code, invoice_no)
VALUES (:ex_a, :ex_doc, '044001900111', '88000011');

DO $$
BEGIN
    BEGIN
        INSERT INTO expense_invoice_claim (tenant_id, document_id, invoice_code, invoice_no)
        VALUES ('aaaaaaaa-0000-4000-8000-0000000000e1',
                'aaaaaaaa-0000-4000-8000-0000000000d1', '044001900111', '88000011');
        RAISE EXCEPTION '重复活动发票号应被唯一索引拒绝';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

    UPDATE expense_invoice_claim
       SET released_at = now(), release_reason = 'rejected'
     WHERE tenant_id = 'aaaaaaaa-0000-4000-8000-0000000000e1';

    INSERT INTO expense_invoice_claim (tenant_id, document_id, invoice_code, invoice_no)
    VALUES ('aaaaaaaa-0000-4000-8000-0000000000e1',
            'aaaaaaaa-0000-4000-8000-0000000000d1', '044001900111', '88000011');
END
$$;

-- 4. 策略默认值与租户隔离
INSERT INTO expense_tenant_policy (tenant_id) VALUES (:ex_a);

DO $$
DECLARE
    policy record;
BEGIN
    SELECT auto_post_enabled, auto_post_limit, window_days, verify_required, min_confidence, version
      INTO policy
      FROM expense_tenant_policy
     WHERE tenant_id = 'aaaaaaaa-0000-4000-8000-0000000000e1';
    IF NOT policy.auto_post_enabled THEN
        RAISE EXCEPTION '默认应允许自动入账';
    END IF;
    IF policy.auto_post_limit <> 5000 THEN
        RAISE EXCEPTION '默认自动入账阈值应为 5000，实际 %', policy.auto_post_limit;
    END IF;
    IF policy.window_days <> 90 THEN
        RAISE EXCEPTION '默认报销窗口应为 90 天，实际 %', policy.window_days;
    END IF;
    IF NOT policy.verify_required THEN
        RAISE EXCEPTION '默认应要求发票验真（转人工）';
    END IF;
    IF policy.min_confidence <> 0.70 THEN
        RAISE EXCEPTION '默认置信度门槛应为 0.70，实际 %', policy.min_confidence;
    END IF;
    IF policy.version <> 1 THEN
        RAISE EXCEPTION '新建策略版本应为 1，实际 %', policy.version;
    END IF;
END
$$;

-- 5. 单据状态与入账/撤销字段一致性
DO $$
BEGIN
    BEGIN
        UPDATE expense_document SET status = 'posted' WHERE id = 'aaaaaaaa-0000-4000-8000-0000000000d1';
        RAISE EXCEPTION 'posted 必须带 posted_at/posted_by/post_actor_kind';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        UPDATE expense_document SET status = 'unknown'
         WHERE id = 'aaaaaaaa-0000-4000-8000-0000000000d1';
        RAISE EXCEPTION '未知状态应被拒绝';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        UPDATE expense_document SET amount = 0
         WHERE id = 'aaaaaaaa-0000-4000-8000-0000000000d1';
        RAISE EXCEPTION '金额必须大于 0';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        UPDATE expense_document
           SET status = 'voided', voided_at = now(), voided_by = 'aaaaaaaa-0000-4000-8000-0000000000a2',
               void_reason = '   '
         WHERE id = 'aaaaaaaa-0000-4000-8000-0000000000d1';
        RAISE EXCEPTION '撤销必须填写非空原因';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
END
$$;

-- 6. 权限码已进入目录
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(expected.code, ',') INTO missing
      FROM (VALUES ('expense.document.read'), ('expense.document.submit'),
                   ('expense.document.review'), ('expense.document.post'),
                   ('expense.document.void'), ('expense.policy.manage')) AS expected(code)
     WHERE NOT EXISTS (SELECT 1 FROM authorization_permission p WHERE p.code = expected.code);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION '缺少单据预审权限码：%', missing;
    END IF;
END
$$;

-- 7. 批量异常暂停：RLS、active 唯一、释放状态一致性、禁止删除
BEGIN;
SELECT set_config('app.tenant_id', :ex_a, true);
INSERT INTO expense_batch_hold
    (tenant_id, scope_type, scope_key, reason, document_count, amount_total, created_by)
VALUES (:ex_a, 'vendor', 'acme 供应商', '24 小时内同一供应商提交 5 张单据',
        5, 640.00, 'aaaaaaaa-0000-4000-8000-0000000000a1');

DO $$
BEGIN
    BEGIN
        INSERT INTO expense_batch_hold
            (tenant_id, scope_type, scope_key, reason, document_count)
        VALUES ('aaaaaaaa-0000-4000-8000-0000000000e1', 'vendor', 'acme 供应商',
                '重复 active 记录', 5);
        RAISE EXCEPTION '同范围重复 active 暂停应被唯一索引拒绝';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO expense_batch_hold
            (tenant_id, scope_type, scope_key, reason, document_count, status,
             released_at, released_by)
        VALUES ('aaaaaaaa-0000-4000-8000-0000000000e1', 'amount', 'amount:99.00',
                '缺少释放备注', 5, 'released', now(),
                'aaaaaaaa-0000-4000-8000-0000000000a2');
        RAISE EXCEPTION 'released 必须带释放备注';
    EXCEPTION WHEN check_violation THEN NULL;
    END;

    BEGIN
        DELETE FROM expense_batch_hold
         WHERE tenant_id = 'aaaaaaaa-0000-4000-8000-0000000000e1';
        RAISE EXCEPTION '批量异常暂停记录应不可删除';
    EXCEPTION WHEN raise_exception THEN NULL;
    END;
END
$$;

UPDATE expense_batch_hold
   SET status = 'released', released_at = now(),
       released_by = 'aaaaaaaa-0000-4000-8000-0000000000a2', release_note = '已核对为正常批量报销'
 WHERE tenant_id = :ex_a AND status = 'active';

INSERT INTO expense_batch_hold
    (tenant_id, scope_type, scope_key, reason, document_count)
VALUES (:ex_a, 'vendor', 'acme 供应商', '释放后再次触发', 6);
COMMIT;

BEGIN;
SELECT set_config('app.tenant_id', :ex_b, true);
DO $$
DECLARE
    holds integer;
BEGIN
    SELECT count(*) INTO holds FROM expense_batch_hold;
    IF holds <> 0 THEN
        RAISE EXCEPTION '跨租户不应看到批量异常暂停，实际 % 行', holds;
    END IF;
    BEGIN
        INSERT INTO expense_batch_hold
            (tenant_id, scope_type, scope_key, reason, document_count)
        VALUES ('aaaaaaaa-0000-4000-8000-0000000000e1', 'uploader',
                'aaaaaaaa-0000-4000-8000-0000000000a1', '越权写入', 5);
        RAISE EXCEPTION '乙租户不能写入甲租户的暂停记录';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
END
$$;
COMMIT;

SELECT 'WP04-03 单据预审数据库断言通过' AS result;
