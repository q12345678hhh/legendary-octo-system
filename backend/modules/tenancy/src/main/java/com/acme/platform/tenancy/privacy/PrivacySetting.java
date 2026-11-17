package com.acme.platform.tenancy.privacy;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 租户隐私设置；数据库中缺行时使用 {@link #defaults(UUID)}。 */
public record PrivacySetting(
        UUID tenantId,
        String dataResidency,
        boolean trainingOptOut,
        int voiceTranscriptRetentionDays,
        int expenseOcrRetentionDays,
        String updatedBy,
        OffsetDateTime updatedAt) {

    public static final String DEFAULT_RESIDENCY = "cn";
    public static final int DEFAULT_VOICE_DAYS = 365;
    public static final int DEFAULT_OCR_DAYS = 3650;

    public static PrivacySetting defaults(UUID tenantId) {
        return new PrivacySetting(tenantId, DEFAULT_RESIDENCY, true,
                DEFAULT_VOICE_DAYS, DEFAULT_OCR_DAYS, null, null);
    }
}
