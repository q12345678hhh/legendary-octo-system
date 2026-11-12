package com.acme.platform.authorization;

import java.util.Optional;
import org.springframework.stereotype.Component;

/** 职责分离检查默认实现：全部规则来自 {@link SodRuleCatalog}。 */
@Component
public class SodChecker implements SeparationOfDutyChecker {

    @Override
    public Result check(AuthorizationSubject subject, String permission) {
        Optional<SodRuleCatalog.Conflict> conflict = SodRuleCatalog.conflict(subject.actorId(),
                subject.matchingRoleCodes(), subject.allRoleCodes(), permission,
                subject.attributes());
        return conflict.map(value -> Result.blocked(value.code(), value.detail()))
                .orElseGet(Result::permit);
    }
}
