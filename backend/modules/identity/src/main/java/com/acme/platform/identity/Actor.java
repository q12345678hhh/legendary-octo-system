package com.acme.platform.identity;

import java.util.UUID;

/** 统一主体；会话与令牌都绑定 actorId，审计只记主体而不区分来源表。 */
public record Actor(UUID id,
                    UUID tenantId,
                    ActorType actorType,
                    String displayName,
                    String status,
                    UUID userId) {
}
