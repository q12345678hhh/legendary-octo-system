package com.acme.platform.worklog.voice;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 一次语音录入的对象存储键、转写文本与删除状态。 */
public record VoiceCapture(
        UUID id,
        UUID personId,
        UUID employmentId,
        LocalDate workDate,
        String objectKey,
        String contentType,
        long sizeBytes,
        Integer durationSeconds,
        String status,
        String transcriptText,
        String editedText,
        String provider,
        String model,
        Double confidence,
        String errorCode,
        UUID worklogEntryId,
        OffsetDateTime transcribedAt,
        OffsetDateTime audioDeletedAt,
        OffsetDateTime createdAt) {
}
