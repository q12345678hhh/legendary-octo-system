package com.acme.platform.identity;

import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ActorContextHolder;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import org.springframework.stereotype.Component;

/**
 * 认证主体的最小授权。
 *
 * <p>WP02-04 之前没有统一权限模型：管理动作只要求成员是 owner/admin，
 * 高权限动作再额外要求本次会话已完成第二因子。
 */
@Component
public class IdentityGuard {

    private final TenantTransactions tenantTransactions;
    private final MemberStore memberStore;

    public IdentityGuard(TenantTransactions tenantTransactions, MemberStore memberStore) {
        this.tenantTransactions = tenantTransactions;
        this.memberStore = memberStore;
    }

    public ActorContext requireActor() {
        return ActorContextHolder.require();
    }

    public ActorContext requireUser() {
        ActorContext actor = ActorContextHolder.require();
        if (actor.userId() == null) {
            throw ApiException.forbidden("USER_REQUIRED", "该操作仅限登录用户");
        }
        return actor;
    }

    public TenantMember requireAdmin(boolean requireMfa) {
        ActorContext actor = requireUser();
        if (actor.memberId() == null) {
            throw ApiException.forbidden("MEMBER_REQUIRED", "当前账号不是租户成员");
        }
        TenantMember member = tenantTransactions.inTenant(ctx ->
                memberStore.findById(actor.memberId()).orElse(null));
        if (member == null) {
            throw ApiException.forbidden("MEMBER_REQUIRED", "当前账号不是租户成员");
        }
        if (member.status() != MemberStatus.ACTIVE) {
            throw ApiException.forbidden("MEMBER_INACTIVE", "成员已停用");
        }
        if (!member.owner() && !member.admin()) {
            throw ApiException.forbidden("MEMBER_NOT_ADMIN", "需要管理员权限");
        }
        if (requireMfa && !actor.mfaSatisfied()) {
            throw ApiException.forbidden("MFA_REQUIRED", "该操作要求先完成 MFA 验证");
        }
        return member;
    }
}
