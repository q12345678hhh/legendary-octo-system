package com.acme.platform.authorization;

import java.util.UUID;

/**
 * 候选授权：显式授权或组织派生授权。
 *
 * @param source 来源标记（ASSIGNMENT/TENANT_OWNER/MEMBER_ADMIN/BUSINESS_OWNER/MANAGER/
 *               DELEGATION/EMPLOYEE），用于解释器与审计
 */
public record RoleGrant(String roleCode, ScopeType scopeType, UUID scopeId, String source,
                        UUID assignmentId) {

    public static RoleGrant derived(String roleCode, ScopeType scopeType, UUID scopeId,
                                    String source) {
        return new RoleGrant(roleCode, scopeType, scopeId, source, null);
    }
}
