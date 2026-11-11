package com.acme.platform.platform.job;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 异步作业视图。payload/result 以 JSON 原文保存，模块内不做业务解释。
 */
public record JobRecord(
        UUID id,
        UUID tenantId,
        String subjectId,
        String jobType,
        String status,
        int progress,
        boolean cancelRequested,
        String payload,
        String result,
        String lastError,
        String traceId,
        String correlationId,
        OffsetDateTime requestedAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {

    public boolean terminal() {
        return "succeeded".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }
}
