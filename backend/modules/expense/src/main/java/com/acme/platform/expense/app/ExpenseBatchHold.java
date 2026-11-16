package com.acme.platform.expense.app;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 批量异常暂停记录。
 *
 * <p>同一租户、同一范围只允许一条 active 记录；释放后保留痕迹，不删除。
 */
public record ExpenseBatchHold(
        UUID id,
        String scopeType,
        String scopeKey,
        String reason,
        int windowHours,
        int documentCount,
        BigDecimal amountTotal,
        String status,
        UUID createdBy,
        OffsetDateTime createdAt,
        UUID releasedBy,
        OffsetDateTime releasedAt,
        String releaseNote) {

    public boolean active() {
        return "active".equals(status);
    }
}
