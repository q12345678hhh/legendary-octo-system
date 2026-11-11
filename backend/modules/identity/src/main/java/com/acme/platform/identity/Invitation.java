package com.acme.platform.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 邀请/激活记录；令牌哈希只在库里，明文由创建接口返回一次。 */
public record Invitation(UUID id,
                         UUID tenantId,
                         UUID userId,
                         UUID memberId,
                         String email,
                         OffsetDateTime expiresAt,
                         OffsetDateTime acceptedAt,
                         OffsetDateTime revokedAt) {
}
