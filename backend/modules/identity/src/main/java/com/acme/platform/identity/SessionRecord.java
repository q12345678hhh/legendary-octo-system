package com.acme.platform.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 会话记录；tokenHash 是唯一可检索凭据，明文只在登录响应出现一次。 */
public record SessionRecord(UUID id,
                            UUID tenantId,
                            UUID userId,
                            UUID actorId,
                            String tokenHash,
                            int authVersion,
                            boolean mfaSatisfied,
                            UUID deviceId,
                            String deviceLabel,
                            String ip,
                            String userAgent,
                            OffsetDateTime createdAt,
                            OffsetDateTime lastSeenAt,
                            OffsetDateTime idleExpiresAt,
                            OffsetDateTime expiresAt,
                            OffsetDateTime revokedAt) {
}
