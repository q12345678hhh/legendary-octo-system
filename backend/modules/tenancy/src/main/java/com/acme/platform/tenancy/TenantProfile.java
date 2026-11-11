package com.acme.platform.tenancy;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 租户品牌与区域配置。
 *
 * <p>{@code features} 是功能开关，只能是布尔值：套餐给出默认值，平台可在套餐范围内覆盖。
 */
public record TenantProfile(
        UUID tenantId,
        String brandName,
        String brandColor,
        String locale,
        String timezone,
        Map<String, Boolean> features,
        OffsetDateTime updatedAt,
        String updatedBy) {
}
