package com.acme.platform.collaboration;

import java.util.Set;
import java.util.UUID;

/**
 * 协作请求的可见范围：由统一授权决策解析后传入，服务层只按它下推 SQL。
 *
 * @param personId   当前主体绑定的员工；未绑定为 null（只能看全员频道）
 * @param tenantWide 是否租户级范围
 * @param orgUnitIds 可见部门；租户级范围时忽略
 */
public record CollabViewer(UUID personId, boolean tenantWide, Set<UUID> orgUnitIds) {

    public CollabViewer {
        orgUnitIds = orgUnitIds == null ? Set.of() : Set.copyOf(orgUnitIds);
    }

    public boolean seesUnit(UUID orgUnitId) {
        return tenantWide || (orgUnitId != null && orgUnitIds.contains(orgUnitId));
    }
}
