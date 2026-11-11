package com.acme.platform.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** TOTP 方法；密钥以 AES-256-GCM 密文与随机 nonce 存储。 */
public record MfaMethod(UUID id,
                        UUID tenantId,
                        UUID userId,
                        String secretCiphertext,
                        String secretNonce,
                        MfaStatus status,
                        OffsetDateTime confirmedAt,
                        Long lastUsedStep) {
}
