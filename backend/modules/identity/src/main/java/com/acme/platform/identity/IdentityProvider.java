package com.acme.platform.identity;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** OIDC/SAML/SCIM 预留模型；P0 只允许 draft/disabled。 */
public record IdentityProvider(UUID id,
                               UUID tenantId,
                               String providerType,
                               String name,
                               String status,
                               Map<String, Object> config,
                               OffsetDateTime createdAt,
                               OffsetDateTime updatedAt) {
}
