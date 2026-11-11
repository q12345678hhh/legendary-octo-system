-- 锁等待探针的写入端：迁移期间持续做短事务写入，用真实语句耗时判断是否被阻塞。
--
-- 为什么用 \gexec：每一行生成一条独立语句，psql 以自动提交逐条执行，
-- 每条都是独立短事务——这更接近生产流量。若写成一个大事务，CREATE INDEX CONCURRENTLY
-- 会一直等这个事务结束，量到的等待时间就失去意义。
--
-- 输出：每行 "Time: X ms"，由 scripts/db-verify.ps1 解析成最大/超阈值语句数。
\set ON_ERROR_STOP on
\timing on

SELECT format(
    'INSERT INTO platform_outbox (event_id, tenant_id, event_name, schema_version, producer, correlation_id, occurred_at, payload) VALUES (gen_random_uuid(), %L::uuid, %L, 1, %L, %L, now(), %L::jsonb); SELECT pg_sleep(0.005);',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'platform.probe.v1',
    'probe',
    'corr-probe-' || g,
    '{"seq": ' || g || '}'
)
FROM generate_series(1, 1500) AS g
\gexec
