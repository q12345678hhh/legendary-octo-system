package com.acme.platform.identity;

import java.util.UUID;

/** 租户成员关系：连接账号与自然人，可换账号而不丢失雇佣历史。 */
public record TenantMember(UUID id,
                           UUID tenantId,
                           UUID userId,
                           UUID personId,
                           MemberStatus status,
                           boolean owner,
                           boolean admin,
                           boolean mfaRequired) {
}
