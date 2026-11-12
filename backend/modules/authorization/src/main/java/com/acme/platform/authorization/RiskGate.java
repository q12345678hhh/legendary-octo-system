package com.acme.platform.authorization;

/**
 * 风险门禁扩展点：设备信任、网络、地域与风险等级在后续安全模块实现。
 *
 * <p>默认放行；实现方返回 true 时决策以 {@link DecisionReason#RISK_BLOCKED} 拒绝。
 */
@FunctionalInterface
public interface RiskGate {

    boolean blocked(AuthorizationSubject subject, AccessRequest request);

    record AuthorizationSubject(java.util.UUID actorId, java.util.Set<String> roleCodes) {
    }
}
