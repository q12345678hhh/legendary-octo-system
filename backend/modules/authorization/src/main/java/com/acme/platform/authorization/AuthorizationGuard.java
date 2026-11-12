package com.acme.platform.authorization;

import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ActorContextHolder;
import com.acme.platform.platform.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 业务入口守卫：把当前已认证主体翻译成决策请求，拒绝时抛 403。
 *
 * <p>控制器与服务都通过它取结论；批量、搜索、导出与 AI 工具复用同一实例，
 * 不允许各自实现一套判断。
 */
@Component
public class AuthorizationGuard {

    private final AuthorizationService service;

    public AuthorizationGuard(AuthorizationService service) {
        this.service = service;
    }

    public AccessDecision decide(String permission, String resourceType, UUID resourceId,
                                 Map<String, Object> attributes) {
        return service.check(request(permission, resourceType, resourceId, attributes));
    }

    public AccessDecision require(String permission, String resourceType, UUID resourceId,
                                  Map<String, Object> attributes) {
        AccessDecision decision = decide(permission, resourceType, resourceId, attributes);
        if (!decision.allowed()) {
            throw ApiException.forbidden(decision.reason().code(), decision.reason().message());
        }
        return decision;
    }

    public AccessDecision explain(String permission, String resourceType, UUID resourceId,
                                  Map<String, Object> attributes) {
        return service.explain(request(permission, resourceType, resourceId, attributes));
    }

    public AccessDecision explainFor(UUID actorId, String permission, String resourceType,
                                     UUID resourceId, Map<String, Object> attributes) {
        ActorContext actor = actor();
        return service.explain(new AccessRequest(actor.tenantId(), actorId, permission,
                resourceType, resourceId, attributes));
    }

    public AccessDecision checkFor(UUID actorId, String permission, String resourceType,
                                   UUID resourceId, Map<String, Object> attributes) {
        ActorContext actor = actor();
        return service.check(new AccessRequest(actor.tenantId(), actorId, permission,
                resourceType, resourceId, attributes));
    }

    public void requireMfa() {
        if (!actor().mfaSatisfied()) {
            throw ApiException.forbidden("MFA_REQUIRED", "该操作要求先完成 MFA 验证");
        }
    }

    public ActorContext actor() {
        return ActorContextHolder.current().orElseThrow(() ->
                new ApiException(401, "SESSION_REQUIRED", "请求必须携带会话或 API 令牌"));
    }

    public AccessRequest request(String permission, String resourceType, UUID resourceId,
                                 Map<String, Object> attributes) {
        ActorContext actor = actor();
        return new AccessRequest(actor.tenantId(), actor.actorId(), permission, resourceType,
                resourceId, attributes);
    }
}
