package com.acme.platform.organization;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 职位：部门下的具体编制占位，主/兼岗指向它。 */
public record Position(UUID id,
                       UUID tenantId,
                       UUID orgUnitId,
                       UUID jobId,
                       String code,
                       String name,
                       Integer headcountLimit,
                       MasterDataStatus status,
                       OffsetDateTime createdAt) {
}
