package com.acme.platform.identity.operator;

import java.util.Set;
import java.util.UUID;

/**
 * 平台运营请求的认证主体。与租户域的 {@code ActorContext} 完全独立：
 * 这里没有 tenantId，任何需要租户上下文的数据都必须显式走租户事务。
 */
public record PlatformActorContext(UUID operatorId,
                                   UUID sessionId,
                                   String email,
                                   String displayName,
                                   String roleCode,
                                   boolean mfaSatisfied) {

    public Set<String> permissions() {
        return PlatformPermissionCatalog.permissionsOf(roleCode);
    }
}
