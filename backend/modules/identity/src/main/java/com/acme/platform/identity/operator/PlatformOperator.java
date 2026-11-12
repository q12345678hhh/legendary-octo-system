package com.acme.platform.identity.operator;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 平台运营账号的只读视图；密码哈希与 MFA 密文不外泄。 */
public record PlatformOperator(UUID id,
                               String email,
                               String displayName,
                               String roleCode,
                               String passwordHash,
                               String mfaSecretCipher,
                               String mfaNonce,
                               boolean mfaConfirmed,
                               String status,
                               int failedAttempts,
                               OffsetDateTime lockedUntil,
                               OffsetDateTime lastLoginAt) {

    public boolean active() {
        return "active".equals(status);
    }

    public boolean locked(OffsetDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
