package com.acme.platform.tenancy;

/**
 * 单条配额与使用量。
 *
 * @param key   配额键，例如 {@code users}、{@code storage_mb}、{@code ai_tokens}
 * @param limit 上限
 * @param used  已用量，只增不减
 * @param unit  计量单位
 * @param period 统计周期：day/month/total
 */
public record TenantQuota(String key, long limit, long used, String unit, String period) {

    public long remaining() {
        return Math.max(0, limit - used);
    }
}
