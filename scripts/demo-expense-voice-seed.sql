-- 演示语音与报销数据（仅用于租户 aaaaaaaa-0000-0000-0000-000000000001）。
--
-- 为「隐私与数据 → 我的语音与单据」以及报销列表提供可演示内容：
--   * 2 条语音记录：1 条已确认且音频已删除，1 条待确认；
--   * 3 张单据：人工复核中、已通过、已入账各 1 张。
-- 依赖 demo-base-seed.sql 创建的演示主管（person ...0031）。
-- 脚本可重复执行；已存在的行不会被覆盖。

BEGIN;

SELECT set_config('app.tenant_id', 'aaaaaaaa-0000-0000-0000-000000000001', true);

INSERT INTO platform_file
 (id, tenant_id, subject_id, file_name, content_type, size_bytes, object_key, status,
  scan_status, uploaded_at, scanned_at)
VALUES
 ('33333333-0000-4000-8000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000031', '办公用品发票.pdf', 'application/pdf', 182400,
  'aaaaaaaa-0000-0000-0000-000000000001/seed/expense-1.pdf', 'available', 'clean',
  now() - interval '5 days', now() - interval '5 days'),
 ('33333333-0000-4000-8000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000031', '云服务账单.pdf', 'application/pdf', 96400,
  'aaaaaaaa-0000-0000-0000-000000000001/seed/expense-2.pdf', 'available', 'clean',
  now() - interval '3 days', now() - interval '3 days'),
 ('33333333-0000-4000-8000-000000000003', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000031', '客户拜访打车票.jpg', 'image/jpeg', 421000,
  'aaaaaaaa-0000-0000-0000-000000000001/seed/expense-3.jpg', 'available', 'clean',
  now() - interval '1 day', now() - interval '1 day')
ON CONFLICT (id) DO NOTHING;

INSERT INTO expense_document
 (id, tenant_id, uploader_person_id, file_id, doc_type, status, currency, amount,
  invoice_code, invoice_no, vendor_name, occurred_on, category, findings, confidence,
  rule_version, posted_at, posted_by, post_actor_kind)
VALUES
 ('44444444-0000-4000-8000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000031', '33333333-0000-4000-8000-000000000001',
  'invoice', 'manual_review', 'CNY', 128.50, '011002', '24810001', '星辰文具有限公司',
  current_date - 5, '办公用品',
  '[{"code":"AMOUNT_MISMATCH","status":"warn","message":"价税合计与明细相差 0.50 元", "actual":128.50,"expected":128.00}]'::jsonb,
  '{"amount":0.82,"vendor":0.91,"invoiceNo":0.88}'::jsonb, 'demo-v1', NULL, NULL, NULL),
 ('44444444-0000-4000-8000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000031', '33333333-0000-4000-8000-000000000002',
  'bill', 'approved', 'CNY', 4200.00, NULL, NULL, '云启科技（上海）有限公司',
  current_date - 3, '云服务',
  '[]'::jsonb, '{"amount":0.96,"vendor":0.94}'::jsonb, 'demo-v1', NULL, NULL, NULL),
 ('44444444-0000-4000-8000-000000000003', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000031', '33333333-0000-4000-8000-000000000003',
  'receipt', 'posted', 'CNY', 86.00, NULL, NULL, '城市出行科技',
  current_date - 1, '市内交通',
  '[]'::jsonb, '{"amount":0.93,"vendor":0.90}'::jsonb, 'demo-v1',
  now() - interval '1 day', '11111111-0000-4000-8000-000000000031', 'employee')
ON CONFLICT (id) DO NOTHING;

INSERT INTO expense_document_extraction
 (id, tenant_id, document_id, provider, model, status, ocr_text, fields, confidence,
  latency_ms)
VALUES
 ('55555555-0000-4000-8000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001',
  '44444444-0000-4000-8000-000000000001', 'mock-ocr', 'demo-ocr-1', 'ok',
  '星辰文具有限公司 增值税普通发票 价税合计 ¥128.50', 
  '{"invoiceNo":"24810001","amount":128.50,"currency":"CNY","vendorName":"星辰文具有限公司","occurredOn":"2026-11-12","category":"办公用品"}'::jsonb,
  '{"amount":0.82,"vendor":0.91,"invoiceNo":0.88}'::jsonb, 820),
 ('55555555-0000-4000-8000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001',
  '44444444-0000-4000-8000-000000000002', 'mock-ocr', 'demo-ocr-1', 'ok',
  '云启科技（上海）有限公司 云服务费 ¥4200.00', 
  '{"amount":4200.00,"currency":"CNY","vendorName":"云启科技（上海）有限公司","occurredOn":"2026-11-14","category":"云服务"}'::jsonb,
  '{"amount":0.96,"vendor":0.94}'::jsonb, 760),
 ('55555555-0000-4000-8000-000000000003', 'aaaaaaaa-0000-0000-0000-000000000001',
  '44444444-0000-4000-8000-000000000003', 'mock-ocr', 'demo-ocr-1', 'ok',
  '城市出行科技 电子行程单 ¥86.00', 
  '{"amount":86.00,"currency":"CNY","vendorName":"城市出行科技","occurredOn":"2026-11-16","category":"市内交通"}'::jsonb,
  '{"amount":0.93,"vendor":0.90}'::jsonb, 690)
ON CONFLICT (id) DO NOTHING;

INSERT INTO expense_document_review
 (id, tenant_id, document_id, decision, comment, overrides, actor_person_id)
VALUES
 ('66666666-0000-4000-8000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001',
  '44444444-0000-4000-8000-000000000002', 'approve', '金额与合同一致，同意报销',
  '[]'::jsonb, '11111111-0000-4000-8000-000000000031'),
 ('66666666-0000-4000-8000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001',
  '44444444-0000-4000-8000-000000000003', 'approve', '市内交通，符合差旅标准',
  '[]'::jsonb, '11111111-0000-4000-8000-000000000031')
ON CONFLICT (id) DO NOTHING;

INSERT INTO worklog_voice_capture
 (id, tenant_id, person_id, employment_id, work_date, object_key, content_type,
  size_bytes, duration_seconds, status, transcript_text, edited_text, provider, model,
  confidence, client_request_id, created_at, transcribed_at, audio_deleted_at)
VALUES
 ('77777777-0000-4000-8000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000031', '11111111-0000-4000-8000-000000000041',
  current_date - 2, 'aaaaaaaa-0000-0000-0000-000000000001/seed/voice-1.m4a', 'audio/mp4',
  186320, 46, 'confirmed',
  '今天上午和客户确认了试点范围，下午整理回归用例并同步给测试。',
  '今天上午和客户确认了试点范围，下午整理回归用例并同步给测试。',
  'mock-asr', 'demo-asr-1', 0.92, 'demo-voice-1',
  now() - interval '2 days', now() - interval '2 days' + interval '2 minutes',
  now() - interval '2 days' + interval '3 minutes'),
 ('77777777-0000-4000-8000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000031', '11111111-0000-4000-8000-000000000041',
  current_date, 'aaaaaaaa-0000-0000-0000-000000000001/seed/voice-2.m4a', 'audio/mp4',
  94210, 28, 'transcribed',
  '明天需要和产品确认报表口径，并补一份接口联调计划。',
  NULL, 'mock-asr', 'demo-asr-1', 0.88, 'demo-voice-2',
  now() - interval '3 hours', now() - interval '3 hours' + interval '90 seconds', NULL)
ON CONFLICT (id) DO NOTHING;

COMMIT;
