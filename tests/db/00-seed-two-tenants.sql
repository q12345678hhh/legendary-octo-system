-- 在两个租户下准备最小数据，供 RLS 用例与恢复校验比对。
-- 以迁移账号（platform_owner）执行：它建表、发文，也在恢复演练里充当源库基准。
INSERT INTO tenancy_tenant (id, slug, name, status) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'tenant-a', '租户 A', 'active'),
    ('bbbbbbbb-0000-0000-0000-000000000002', 'tenant-b', '租户 B', 'active')
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_outbox
    (event_id, tenant_id, event_name, schema_version, producer,
     correlation_id, trace_id, occurred_at, payload)
SELECT
    ('00000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid,
    CASE WHEN g % 2 = 0
        THEN 'aaaaaaaa-0000-0000-0000-000000000001'::uuid
        ELSE 'bbbbbbbb-0000-0000-0000-000000000002'::uuid END,
    'platform.seed.row.v1',
    1,
    'seed',
    'corr-seed-' || g,
    NULL,
    now() - (g || ' seconds')::interval,
    jsonb_build_object('seq', g)
FROM generate_series(1, 20) AS g;
