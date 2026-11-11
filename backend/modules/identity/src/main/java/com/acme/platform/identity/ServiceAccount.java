package com.acme.platform.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 服务账号：机器主体，只能使用 API 令牌，不能登录。 */
public record ServiceAccount(UUID id,
                             UUID tenantId,
                             UUID actorId,
                             String name,
                             String description,
                             String status,
                             OffsetDateTime createdAt) {
}
