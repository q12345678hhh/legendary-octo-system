package com.acme.platform.organization;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 办公地点：时区用于考勤与排班的本地日换算。 */
public record Location(UUID id,
                       UUID tenantId,
                       String code,
                       String name,
                       String address,
                       String timezone,
                       MasterDataStatus status,
                       OffsetDateTime createdAt) {
}
