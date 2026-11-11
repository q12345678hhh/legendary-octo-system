package com.acme.platform.organization;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 职级：rank 从 1 起递增。 */
public record JobLevel(UUID id,
                       UUID tenantId,
                       String code,
                       String name,
                       int rank,
                       MasterDataStatus status,
                       OffsetDateTime createdAt) {
}
