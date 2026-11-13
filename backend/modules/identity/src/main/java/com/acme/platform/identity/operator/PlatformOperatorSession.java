package com.acme.platform.identity.operator;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 平台运营会话记录；令牌只存 SHA-256。 */
public record PlatformOperatorSession(UUID id,
                                      UUID operatorId,
                                      String tokenHash,
                                      boolean mfaSatisfied,
                                      String ip,
                                      String userAgent,
                                      OffsetDateTime createdAt,
                                      OffsetDateTime lastSeenAt,
                                      OffsetDateTime idleExpiresAt,
                                      OffsetDateTime expiresAt,
                                      OffsetDateTime revokedAt) {

    public boolean usable(OffsetDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now) && idleExpiresAt.isAfter(now);
    }
}
