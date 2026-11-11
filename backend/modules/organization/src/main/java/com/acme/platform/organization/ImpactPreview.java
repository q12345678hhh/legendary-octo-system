package com.acme.platform.organization;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 部门移动影响预览票据：计数 + 过期时间 + 消费时间。 */
public record ImpactPreview(UUID id,
                            UUID tenantId,
                            UUID unitId,
                            UUID targetParentId,
                            int descendantCount,
                            int employmentCount,
                            int positionCount,
                            int reportingLineCount,
                            int delegationCount,
                            int scheduledChangeCount,
                            String createdBy,
                            OffsetDateTime createdAt,
                            OffsetDateTime expiresAt,
                            OffsetDateTime consumedAt) {

    public boolean consumed() {
        return consumedAt != null;
    }
}
