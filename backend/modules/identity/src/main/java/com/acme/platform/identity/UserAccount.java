package com.acme.platform.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 登录账号视图；不包含密码哈希，避免在应用层无意扩散。 */
public record UserAccount(UUID id,
                          UUID tenantId,
                          String email,
                          String displayName,
                          UserStatus status,
                          int authVersion,
                          boolean mfaRequired,
                          int failedAttempts,
                          OffsetDateTime lockedUntil,
                          OffsetDateTime activatedAt,
                          OffsetDateTime lastLoginAt) {
}
