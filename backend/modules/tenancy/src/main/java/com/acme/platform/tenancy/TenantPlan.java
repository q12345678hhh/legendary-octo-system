package com.acme.platform.tenancy;

import java.util.Map;

/**
 * 平台级套餐目录：默认配额与默认功能开关。
 *
 * <p>本表不含任何租户业务数据，因此不启用 RLS；租户侧读取自己的配额时只读 {@code tenancy_quota}。
 */
public record TenantPlan(String code, String name, Map<String, Long> quotas, Map<String, Boolean> features) {
}
