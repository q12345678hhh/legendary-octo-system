package com.acme.platform.tenancy;

import java.util.UUID;

/**
 * 租户域名。{@code domain} 全局唯一，{@code kind=primary} 每个租户最多一个。
 */
public record TenantDomain(UUID id, UUID tenantId, String domain, String kind, boolean verified) {
}
