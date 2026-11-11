package com.acme.platform.worker.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * outbox 投递参数，全部可通过 {@code PLATFORM_OUTBOX_*} 环境变量覆盖。
 *
 * @param batchSize 单轮最多认领多少条
 * @param maxAttempts 超过该次数即置为 dead，等待人工介入
 * @param retryDelay 失败后推迟多久再投递
 */
@ConfigurationProperties("platform.outbox")
public record OutboxProperties(
        @DefaultValue("20") int batchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("10s") Duration retryDelay) {
}
