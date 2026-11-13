package com.acme.platform.worklog.time;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 工时草稿条目。 */
public record WorklogEntry(UUID id,
                           UUID personId,
                           UUID employmentId,
                           LocalDate workDate,
                           OffsetDateTime startsAt,
                           OffsetDateTime endsAt,
                           int durationMinutes,
                           UUID projectId,
                           UUID taskId,
                           String description,
                           String sourceType,
                           String clientEntryId,
                           int draftVersion) {
}
