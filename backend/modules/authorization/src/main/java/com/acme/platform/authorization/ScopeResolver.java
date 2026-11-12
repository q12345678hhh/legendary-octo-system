package com.acme.platform.authorization;

import com.acme.platform.organization.EmploymentScope;
import com.acme.platform.organization.OrganizationScopeQueries;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 把授权记录上的范围类型解析成可见的人/部门/法人集合。
 *
 * <p>未实现的 {@code project_space} 与 {@code record_shared} 不降级为租户范围，
 * 而是返回 unsupported，由决策层给出显式拒绝。
 */
@Component
public class ScopeResolver {

    public record ScopeResolution(AccessScope scope, boolean supported) {
        public static ScopeResolution unsupported() {
            return new ScopeResolution(AccessScope.none(), false);
        }
    }

    private final OrganizationScopeQueries queries;

    public ScopeResolver(OrganizationScopeQueries queries) {
        this.queries = queries;
    }

    public ScopeResolution resolve(ScopeType scopeType, UUID scopeId, UUID actorPersonId,
                                   EmploymentScope actorScope) {
        if (scopeType == null) {
            return ScopeResolution.unsupported();
        }
        return switch (scopeType) {
            case TENANT -> new ScopeResolution(AccessScope.tenant(), true);
            case SELF -> new ScopeResolution(AccessScope.self(actorPersonId), true);
            case SELF_CREATED -> new ScopeResolution(
                    new AccessScope(false, true, Set.of(), Set.of(), Set.of()), true);
            case DIRECT_REPORTS -> new ScopeResolution(new AccessScope(false, false,
                    actorScope == null ? Set.of() : actorScope.directReportPersonIds(),
                    Set.of(), Set.of()), true);
            case REPORTING_CHAIN -> new ScopeResolution(new AccessScope(false, false,
                    actorScope == null ? Set.of() : actorScope.chainPersonIds(),
                    Set.of(), Set.of()), true);
            case ORG_UNIT -> new ScopeResolution(AccessScope.units(
                    scopeId == null ? Set.of() : Set.of(scopeId)), true);
            case ORG_UNIT_SUBTREE -> new ScopeResolution(
                    AccessScope.units(queries.subtreeUnitIds(scopeId)), true);
            case DELEGATED -> {
                Set<UUID> units = new java.util.HashSet<>();
                if (actorScope != null) {
                    for (UUID delegated : actorScope.delegatedOrgUnitIds()) {
                        units.addAll(queries.subtreeUnitIds(delegated));
                    }
                }
                yield new ScopeResolution(AccessScope.units(units), true);
            }
            case LEGAL_ENTITY -> new ScopeResolution(
                    AccessScope.units(queries.unitsInLegalEntity(scopeId)), true);
            case PROJECT_SPACE, RECORD_SHARED -> ScopeResolution.unsupported();
        };
    }
}
