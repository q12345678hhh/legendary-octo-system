package com.acme.platform.organization;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 组织变更历史：只插入，before/after 是完整快照。 */
public record OrganizationHistoryEntry(UUID id,
                                       UUID tenantId,
                                       UUID changeId,
                                       String entityType,
                                       UUID entityId,
                                       String action,
                                       LocalDate effectiveDate,
                                       String beforeState,
                                       String afterState,
                                       String actor,
                                       OffsetDateTime occurredAt) {
}
