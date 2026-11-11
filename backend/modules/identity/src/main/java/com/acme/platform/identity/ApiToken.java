package com.acme.platform.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** API 令牌视图：不含哈希与明文。 */
public record ApiToken(UUID id,
                       UUID tenantId,
                       UUID serviceAccountId,
                       UUID actorId,
                       String name,
                       String prefix,
                       List<String> scopes,
                       OffsetDateTime expiresAt,
                       OffsetDateTime revokedAt,
                       OffsetDateTime lastUsedAt,
                       OffsetDateTime createdAt) {
}
