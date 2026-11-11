-- 生产级数据量样本：outbox 造 50 万行，用于锁等待评估与执行计划验证。
-- 行数取 50 万的理由：本地与 CI 都能在十几秒内建好，又足以让规划器选择索引、
-- 让并发建索引产生可观测的耗时；GA 前的正式验证按 NFR-23 在 WP11 用真实容量重跑。
INSERT INTO tenancy_tenant (id, slug, name, status) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'tenant-a', '租户 A', 'active'),
    ('bbbbbbbb-0000-0000-0000-000000000002', 'tenant-b', '租户 B', 'active')
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_outbox
    (event_id, tenant_id, event_name, schema_version, producer,
     correlation_id, trace_id, occurred_at, payload, status)
SELECT
    gen_random_uuid(),
    CASE WHEN g % 2 = 0
        THEN 'aaaaaaaa-0000-0000-0000-000000000001'::uuid
        ELSE 'bbbbbbbb-0000-0000-0000-000000000002'::uuid END,
    'platform.bulk.v1',
    1,
    'seed',
    'corr-bulk-' || g,
    NULL,
    now() - (g || ' seconds')::interval,
    jsonb_build_object('seq', g),
    'published'
FROM generate_series(1, 500000) AS g;

ANALYZE platform_outbox;
