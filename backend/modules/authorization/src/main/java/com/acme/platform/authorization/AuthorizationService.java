package com.acme.platform.authorization;

import com.acme.platform.identity.MemberStatus;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.organization.EmploymentScope;
import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ActorContextHolder;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 统一授权决策：默认拒绝、显式拒绝优先、范围下推、条件白名单。
 *
 * <p>顺序固定为：租户与主体 → 成员状态 → 权限是否存在 → 显式拒绝 → 允许集合 → 范围 →
 * 条件 → 职责分离/风险门禁 → 字段规则。任何一步缺失都拒绝，不做隐式放行。
 */
@Service
public class AuthorizationService {

    private final TenantTransactions tenantTransactions;
    private final AuthorizationStore store;
    private final CacheVersionStore versions;
    private final AuthorizationCache cache;
    private final DerivedGrantResolver derivedGrants;
    private final ScopeResolver scopeResolver;
    private final ConditionEvaluator conditions;
    private final FieldPolicyEvaluator fields;
    private final MemberStore memberStore;
    private final OrganizationScopeQueries organizationScopes;
    private final SeparationOfDutyChecker separationOfDuty;
    private final RiskGate riskGate;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AuthorizationService(TenantTransactions tenantTransactions,
                                AuthorizationStore store,
                                CacheVersionStore versions,
                                AuthorizationCache cache,
                                DerivedGrantResolver derivedGrants,
                                ScopeResolver scopeResolver,
                                ConditionEvaluator conditions,
                                FieldPolicyEvaluator fields,
                                MemberStore memberStore,
                                OrganizationScopeQueries organizationScopes,
                                SeparationOfDutyChecker separationOfDuty,
                                RiskGate riskGate,
                                Clock clock,
                                ObjectMapper objectMapper) {
        this.tenantTransactions = tenantTransactions;
        this.store = store;
        this.versions = versions;
        this.cache = cache;
        this.derivedGrants = derivedGrants;
        this.scopeResolver = scopeResolver;
        this.conditions = conditions;
        this.fields = fields;
        this.memberStore = memberStore;
        this.organizationScopes = organizationScopes;
        this.separationOfDuty = separationOfDuty;
        this.riskGate = riskGate;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public AccessDecision check(AccessRequest request) {
        return tenantTransactions.inTenant(context -> decide(request, context, false));
    }

    public AccessDecision explain(AccessRequest request) {
        return tenantTransactions.inTenant(context -> decide(request, context, true));
    }

    public AccessDecision require(AccessRequest request) {
        AccessDecision decision = check(request);
        if (!decision.allowed()) {
            throw ApiException.forbidden(decision.reason().code(), decision.reason().message());
        }
        return decision;
    }

    public long currentVersion() {
        return tenantTransactions.inTenant(context -> versions.currentVersion());
    }

    public List<AuthorizationStore.DecisionLogRow> audit(int limit) {
        return tenantTransactions.inTenant(context -> store.decisionLogs(limit));
    }

    private AccessDecision decide(AccessRequest request, RequestContext context, boolean explain) {
        if (request.tenantId() == null || !request.tenantId().equals(context.tenantId())) {
            return deny(DecisionReason.TENANT_MISSING, 0L, now(), "TENANT_MISMATCH");
        }
        long version = versions.currentVersion();
        String key = cache.key(context.tenantId(), request.actorId(), request, version);
        if (!explain) {
            Optional<AccessDecision> cached = cache.get(key, version);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        AccessDecision decision = evaluate(request, version, now(), LocalDate.now(clock));
        cache.put(key, decision, version);
        if (!decision.allowed() || explain) {
            logDecision(request, decision);
        }
        return decision;
    }

    private AccessDecision evaluate(AccessRequest request, long version, OffsetDateTime now,
                                    LocalDate today) {
        if (request.actorId() == null) {
            return deny(DecisionReason.SUBJECT_MISSING, version, now, "NO_SUBJECT");
        }
        Optional<AuthorizationStore.ActorRow> actor = store.findActor(request.actorId());
        if (actor.isEmpty() || !"active".equals(actor.get().status())) {
            return deny(DecisionReason.SUBJECT_MISSING, version, now, "ACTOR_INACTIVE");
        }

        ActorContext actorContext = ActorContextHolder.current().orElse(null);
        TenantMember member = null;
        if ("user".equals(actor.get().actorType())
                && actorContext != null
                && request.actorId().equals(actorContext.actorId())) {
            if (actorContext.memberId() == null) {
                return deny(DecisionReason.MEMBER_INACTIVE, version, now, "NO_MEMBER");
            }
            member = memberStore.findById(actorContext.memberId()).orElse(null);
            if (member == null || member.status() != MemberStatus.ACTIVE) {
                return deny(DecisionReason.MEMBER_INACTIVE, version, now, "MEMBER_INACTIVE");
            }
        }

        if (!PermissionCatalog.exists(request.permission())) {
            return deny(DecisionReason.PERMISSION_UNKNOWN, version, now, "UNKNOWN_PERMISSION");
        }

        EmploymentScope employment = member != null && member.personId() != null
                ? organizationScopes.scopeOfPerson(member.personId(), today).orElse(null)
                : null;

        List<RoleGrant> grants = new ArrayList<>();
        for (AuthorizationStore.AssignmentRow assignment
                : store.activeAssignments(request.actorId(), now)) {
            grants.add(new RoleGrant(assignment.roleCode(), assignment.scopeType(),
                    assignment.scopeId(), "ASSIGNMENT", assignment.id()));
        }
        grants.addAll(derivedGrants.resolve(member, employment));

        Set<String> allRoles = new LinkedHashSet<>();
        for (RoleGrant grant : grants) {
            allRoles.add(grant.roleCode());
        }
        Map<String, Set<String>> customPermissions = store.customRolePermissions(allRoles);

        for (AuthorizationStore.DenyRow deny
                : store.activeDenies(request.actorId(), allRoles, request.permission(), now)) {
            if (denyMatches(deny, request)) {
                return deny(DecisionReason.EXPLICIT_DENY, version, now, "DENY:" + deny.id());
            }
        }

        List<RoleGrant> matching = grants.stream()
                .filter(grant -> permissionsOf(grant.roleCode(), customPermissions)
                        .contains(request.permission()))
                .toList();
        if (matching.isEmpty()) {
            return deny(DecisionReason.NO_ALLOW, version, now, "NO_MATCHING_GRANT");
        }

        AccessScope scope = AccessScope.none();
        boolean anyScope = false;
        boolean unsupported = false;
        for (RoleGrant grant : matching) {
            ScopeResolver.ScopeResolution resolution = scopeResolver.resolve(grant.scopeType(),
                    grant.scopeId(), member == null ? null : member.personId(), employment);
            if (!resolution.supported()) {
                unsupported = true;
                continue;
            }
            scope = scope.merge(resolution.scope());
            anyScope = true;
        }
        if (!anyScope) {
            return deny(unsupported ? DecisionReason.SCOPE_UNSUPPORTED : DecisionReason.SCOPE_MISS,
                    version, now, unsupported ? "UNSUPPORTED_SCOPE" : "EMPTY_SCOPE");
        }
        if (request.resourceId() != null
                && !resourceVisible(request, scope, request.actorId(),
                        member == null ? null : member.personId(), today)) {
            return deny(DecisionReason.SCOPE_MISS, version, now, "RESOURCE_OUT_OF_SCOPE");
        }

        for (AuthorizationStore.ConditionRow condition
                : store.conditionPolicies(request.permission())) {
            boolean passed = conditions.passes(condition.condition(), request.attributes());
            if ("allow".equals(condition.effect()) && !passed) {
                return deny(DecisionReason.CONDITION_FAILED, version, now,
                        "CONDITION:" + condition.name());
            }
            if ("deny".equals(condition.effect()) && passed) {
                return deny(DecisionReason.CONDITION_FAILED, version, now,
                        "CONDITION:" + condition.name());
            }
        }

        Set<String> matchingRoles = new LinkedHashSet<>();
        List<String> factors = new ArrayList<>();
        for (RoleGrant grant : matching) {
            matchingRoles.add(grant.roleCode());
            factors.add("GRANT:" + grant.roleCode() + ":" + grant.source());
        }

        if (separationOfDuty.conflicts(
                new SeparationOfDutyChecker.AuthorizationSubject(request.actorId(), matchingRoles),
                request.permission())) {
            return AccessDecision.deny(DecisionReason.SOD_CONFLICT, version, now, factors);
        }
        if (riskGate.blocked(
                new RiskGate.AuthorizationSubject(request.actorId(), matchingRoles), request)) {
            return AccessDecision.deny(DecisionReason.RISK_BLOCKED, version, now, factors);
        }

        Map<String, FieldAccess> fieldRules = fields.rules(allRoles, request.resourceType());
        return new AccessDecision(true, DecisionReason.ALLOWED, factors, scope, fieldRules,
                version, now, false);
    }

    private Set<String> permissionsOf(String roleCode, Map<String, Set<String>> customPermissions) {
        if (SystemRoleCatalog.exists(roleCode)) {
            return SystemRoleCatalog.permissions(roleCode);
        }
        return customPermissions.getOrDefault(roleCode, Set.of());
    }

    private boolean denyMatches(AuthorizationStore.DenyRow deny, AccessRequest request) {
        if (deny.resourceType() != null
                && !deny.resourceType().equals(request.resourceType())) {
            return false;
        }
        if (deny.resourceId() != null && !deny.resourceId().equals(request.resourceId())) {
            return false;
        }
        ScopeType scopeType = deny.scopeType();
        if (scopeType == null) {
            return true;
        }
        return switch (scopeType) {
            case TENANT -> true;
            case SELF, SELF_CREATED -> true;
            case ORG_UNIT, ORG_UNIT_SUBTREE ->
                    deny.scopeId() != null
                            && deny.scopeId().equals(asUuid(request.attributes().get("orgUnitId")));
            case LEGAL_ENTITY ->
                    deny.scopeId() != null
                            && deny.scopeId().equals(asUuid(request.attributes().get("legalEntityId")));
            case DIRECT_REPORTS, REPORTING_CHAIN, DELEGATED, PROJECT_SPACE, RECORD_SHARED -> false;
        };
    }

    private boolean resourceVisible(AccessRequest request, AccessScope scope, UUID actorId,
                                    UUID actorPersonId, LocalDate today) {
        if (resourceInScope(request, scope, actorId, actorPersonId)) {
            return true;
        }
        UUID personId = asUuid(request.attributes().get("personId"));
        return personId != null && organizationScopes.primaryUnitOfPerson(personId, today)
                .map(scope::includesOrgUnit).orElse(false);
    }

    static boolean resourceInScope(AccessRequest request, AccessScope scope, UUID actorId,
                                   UUID actorPersonId) {
        Map<String, Object> attributes = request.attributes();
        UUID personId = asUuid(attributes.get("personId"));
        if (personId != null && scope.includesPerson(personId)) {
            return true;
        }
        UUID ownerId = asUuid(attributes.get("ownerId"));
        if (ownerId != null) {
            if (scope.selfCreated() && ownerId.equals(actorId)) {
                return true;
            }
            if (scope.selfCreated() && actorPersonId != null
                    && scope.includesPerson(actorPersonId)) {
                return true;
            }
            if (scope.includesPerson(ownerId)) {
                return true;
            }
        }
        UUID orgUnitId = asUuid(attributes.get("orgUnitId"));
        if (orgUnitId != null && scope.includesOrgUnit(orgUnitId)) {
            return true;
        }
        UUID legalEntityId = asUuid(attributes.get("legalEntityId"));
        return legalEntityId != null && scope.includesLegalEntity(legalEntityId);
    }

    static UUID asUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private AccessDecision deny(DecisionReason reason, long version, OffsetDateTime now,
                                String... factors) {
        return AccessDecision.deny(reason, version, now, List.of(factors));
    }

    private void logDecision(AccessRequest request, AccessDecision decision) {
        try {
            store.insertDecisionLog(request.actorId(), request.permission(),
                    request.resourceType(), request.resourceId(), decision.allowed(),
                    decision.reason().code(), objectMapper.writeValueAsString(decision.factors()));
        } catch (Exception ignored) {
            // 审计写入失败不能反过来改变业务结论；拒绝本身已经发生
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
