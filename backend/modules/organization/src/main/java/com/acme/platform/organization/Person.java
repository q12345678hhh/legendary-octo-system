package com.acme.platform.organization;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 自然人：员工、候选人或外部协作者，不等同于登录账号。
 *
 * <p>没有账号的员工也必须有 Person 与 Employment；账号只是 {@code identity_tenant_member}
 * 上的一次关联，可更换而不影响雇佣历史。
 */
public record Person(UUID id,
                     UUID tenantId,
                     String displayName,
                     String primaryEmail,
                     PersonStatus status,
                     OffsetDateTime createdAt) {
}
