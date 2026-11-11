package com.acme.platform.worker.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * outbox 存储端口。抽取成接口是为了让调度器能脱离数据库做单元测试。
 */
public interface OutboxStore {

    /**
     * 认领一批到期的待投递事件，并把它们置为 {@code processing}。
     * 用 {@code FOR UPDATE SKIP LOCKED} 保证多 Worker 并发时同一行不会被重复认领。
     */
    List<OutboxEvent> claim(int batchSize);

    void markPublished(UUID eventId);

    /**
     * 记录一次失败：未达上限退回 {@code failed} 并推迟到 {@code retryDelay} 之后，达到上限置为 {@code dead}。
     */
    void markFailed(UUID eventId, String error, int maxAttempts, Duration retryDelay);
}
