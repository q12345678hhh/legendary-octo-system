package com.acme.platform;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.collaboration.CollabViewer;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 把统一授权结论翻译成协作域可见范围；personId 永远从会话解析。 */
@Component
class CollabActorResolver {

    private final AuthorizationGuard authorization;
    private final MemberStore memberStore;

    CollabActorResolver(AuthorizationGuard authorization, MemberStore memberStore) {
        this.authorization = authorization;
        this.memberStore = memberStore;
    }

    CollabViewer viewer(AccessDecision decision) {
        return new CollabViewer(personIdOrNull(), decision.scope().tenantWide(),
                decision.scope().orgUnitIds());
    }

    UUID personIdOrNull() {
        ActorContext actor = authorization.actor();
        if (actor == null || actor.memberId() == null) {
            return null;
        }
        return memberStore.findById(actor.memberId())
                .map(TenantMember::personId)
                .orElse(null);
    }

    UUID requirePerson() {
        UUID personId = personIdOrNull();
        if (personId == null) {
            throw ApiException.forbidden("PERSON_NOT_BOUND", "当前账号未绑定员工");
        }
        return personId;
    }
}
