package com.acme.platform.organization;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 法人主体：部门与成本中心必须归属一个法人。 */
public record LegalEntity(UUID id,
                          UUID tenantId,
                          String code,
                          String name,
                          String registrationNo,
                          MasterDataStatus status,
                          OffsetDateTime createdAt) {
}
