-- 索引与执行计划：投递扫描必须走部分索引，不能退化成全表扫描。
-- 输出交给脚本断言，这里只负责稳定地产生计划。
EXPLAIN (COSTS OFF)
SELECT event_id
  FROM platform_outbox
 WHERE status IN ('pending', 'failed')
   AND available_at <= now()
 ORDER BY available_at
 LIMIT 10;
