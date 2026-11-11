package com.acme.platform.organization;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 岗位定义（职务），与具体部门解耦；职位才是部门下的编制占位。 */
public record Job(UUID id,
                  UUID tenantId,
                  String code,
                  String name,
                  String jobFamily,
                  UUID defaultLevelId,
                  MasterDataStatus status,
                  OffsetDateTime createdAt) {
}
