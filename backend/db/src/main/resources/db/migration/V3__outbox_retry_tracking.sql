-- outbox 重试追踪，同时作为「大表变更的加锁决策」样本（WP01-03）。
--
-- 生产级数据量下两类变更的加锁行为完全不同：
-- 1. ADD COLUMN 不带默认值：PostgreSQL 只改元数据，持 ACCESS EXCLUSIVE 的时间是毫秒级，
--    但仍是强锁，必须在低峰执行，且前面不能排长事务。
-- 2. CREATE INDEX: 普通建索引会阻塞写直到建完；CONCURRENTLY 不阻塞写，代价是耗时更长、
--    失败会留下 INVALID 索引需要重建。本文件用 CONCURRENTLY，因此必须在事务外执行，
--    由同名 .conf 的 executeInTransaction=false 声明。

ALTER TABLE platform_outbox ADD COLUMN last_attempt_at timestamptz;

COMMENT ON COLUMN platform_outbox.last_attempt_at IS '最近一次投递尝试时间，用于区分「从未投递」与「重试中」';

-- 并发建索引：已有事务在表上读写时只会等待在建索引阶段，不会阻塞业务写入。
CREATE INDEX CONCURRENTLY IF NOT EXISTS platform_outbox_tenant_status_idx
    ON platform_outbox (tenant_id, status, occurred_at DESC);

COMMENT ON INDEX platform_outbox_tenant_status_idx IS
    '按租户与状态查询投递进度；CONCURRENTLY 创建，不阻塞业务写入';
