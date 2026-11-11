package com.acme.platform.organization;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 管理委派（代管）：限时、必须填写原因，范围是某个部门子树。 */
public record Delegation(UUID id,
                         UUID tenantId,
                         UUID delegatorEmploymentId,
                         UUID delegateEmploymentId,
                         UUID scopeOrgUnitId,
                         String reason,
                         LocalDate effectiveFrom,
                         LocalDate effectiveTo,
                         String createdBy,
                         OffsetDateTime createdAt) {
}
