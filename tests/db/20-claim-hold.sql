-- 并发认领：会话 1 持有最旧一行 3 秒，验证会话 2 不会拿到同一行。
-- FOR UPDATE SKIP LOCKED 是 outbox 多 Worker 不重复投递的基础，这里只验证锁语义，不投递。
BEGIN;
SELECT event_id
  FROM platform_outbox
 WHERE status IN ('pending', 'failed')
   AND available_at <= now()
 ORDER BY available_at
 LIMIT 1
   FOR UPDATE SKIP LOCKED;
SELECT pg_sleep(3);
COMMIT;
