package com.acme.platform.authorization;

import java.util.Map;
import java.util.UUID;

/**
 * 一次访问判定请求。
 *
 * <p>{@code resourceId} 为空表示列表/搜索类请求：决策返回可见范围，由查询层下推；
 * 记录级请求必须带能定位归属的属性（personId/orgUnitId/legalEntityId/ownerId），
 * 缺失时按范围不命中处理，不做模糊放行。
 */
public record AccessRequest(UUID tenantId,
                            UUID actorId,
                            String permission,
                            String resourceType,
                            UUID resourceId,
                            Map<String, Object> attributes) {

    public AccessRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
