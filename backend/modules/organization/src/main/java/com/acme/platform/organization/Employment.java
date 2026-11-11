package com.acme.platform.organization;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 一段雇佣关系；同一自然人可同时存在多条 active 记录。 */
public record Employment(UUID id,
                         UUID tenantId,
                         UUID personId,
                         String employmentNo,
                         EmploymentStatus status,
                         LocalDate effectiveFrom,
                         LocalDate effectiveTo,
                         String jobTitle,
                         OffsetDateTime createdAt) {
}
