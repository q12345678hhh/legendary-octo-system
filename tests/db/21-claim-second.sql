-- 并发认领的第二个会话：在会话 1 持锁期间执行，必须拿到另一行。
BEGIN;
SELECT event_id
  FROM platform_outbox
 WHERE status IN ('pending', 'failed')
   AND available_at <= now()
 ORDER BY available_at
 LIMIT 1
   FOR UPDATE SKIP LOCKED;
COMMIT;
