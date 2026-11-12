package com.acme.platform.authorization;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 职责分离检查扩展点：在范围与条件之后、字段规则之前调用。
 *
 * <p>返回值携带稳定的原因码，调用方据此区分「互斥角色」与「属性缺失」，
 * 而不是把所有冲突都压成同一个 403。
 */
@FunctionalInterface
public interface SeparationOfDutyChecker {

    Result check(AuthorizationSubject subject, String permission);

    /** 判定所需的主体视图：全部角色、命中角色与请求属性。 */
    record AuthorizationSubject(UUID actorId, Set<String> matchingRoleCodes,
                                Set<String> allRoleCodes, Map<String, Object> attributes) {

        public AuthorizationSubject {
            matchingRoleCodes = matchingRoleCodes == null ? Set.of() : Set.copyOf(matchingRoleCodes);
            allRoleCodes = allRoleCodes == null ? Set.of() : Set.copyOf(allRoleCodes);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    record Result(boolean allowed, String code, String detail) {

        public static Result permit() {
            return new Result(true, null, null);
        }

        public static Result blocked(String code, String detail) {
            return new Result(false, code, detail);
        }
    }
}
