package com.acme.platform.organization;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 成本中心：必须归属法人，报表维度在 Phase 7 接入。 */
public record CostCenter(UUID id,
                         UUID tenantId,
                         String code,
                         String name,
                         UUID legalEntityId,
                         MasterDataStatus status,
                         OffsetDateTime createdAt) {
}
