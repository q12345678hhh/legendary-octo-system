package com.acme.platform.platform.audit;

import java.util.UUID;

/** 平台写操作的审计主体：只带运营身份，不带任何租户上下文。 */
public record PlatformOperatorRef(UUID operatorId, String email) {
}
