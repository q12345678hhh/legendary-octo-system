package com.acme.platform.authorization;

import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.organization.EmploymentScope;
import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ActorContextHolder;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 授权治理：角色、授权、显式拒绝、字段/条件策略与权限模拟。
 *
 * <p>所有写入都在租户事务内完成；临时授权与委托的有效期在服务层校验，
 * 数据库只兜住结构约束。模拟不落库，也不改变缓存版本。
 */
@Service
public class AuthorizationAdminService {

    public record RoleView(String code, String name, String description, boolean system,
                           String status, Set<String> permissions) {
    }

    public record CreateRoleCommand(String code, String name, String description,
                                    Set<String> permissions) {
    }

    public record GrantCommand(UUID actorId, UUID memberId, String roleCode, String scopeType,
                               UUID scopeId, OffsetDateTime validFrom, OffsetDateTime validTo,
                               String reason, UUID delegatedFromId, UUID requestId) {
    }

    public record SeparationConflictView(UUID actorId, Set<String> roleCodes, String group,
                                         String detail) {
    }

    public record DenyCommand(UUID actorId, String roleCode, String permissionCode,
                              String resourceType, UUID resourceId, String scopeType,
                              UUID scopeId, String reason, OffsetDateTime expiresAt) {
    }

    public record FieldPolicyCommand(String roleCode, String resourceType, String fieldName,
                                     boolean canView, boolean canEdit, boolean canExport,
                                     boolean canUseAi, String effect) {
    }

    public record ConditionCommand(String permissionCode, String name, String conditionJson,
                                   String effect, int priority) {
    }

    public record SimulatedGrant(String roleCode, String scopeType, UUID scopeId) {
    }

    public record SimulatedDeny(String permissionCode, String resourceType, UUID resourceId) {
    }

    public record SimulationCheck(String permission, String resourceType, UUID resourceId,
                                  Map<String, Object> attributes) {
        public SimulationCheck {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record SimulationCommand(List<SimulatedGrant> grants, List<SimulatedDeny> denies,
                                    List<SimulationCheck> checks) {
    }

    public record SimulationResult(List<AccessDecision> decisions) {
    }

    private final TenantTransactions tenantTransactions;
    private final AuthorizationStore store;
    private final AuthorizationCache cache;
    private final CacheVersionStore versions;
    private final ScopeResolver scopeResolver;
    private final ConditionEvaluator conditions;
    private final OrganizationScopeQueries organizationScopes;
    private final MemberStore memberStore;
    private final AccessRequestStore accessRequests;
    private final AdminActionStore adminActions;
    private final AuthorizationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuthorizationAdminService(TenantTransactions tenantTransactions,
                                     AuthorizationStore store,
                                     AuthorizationCache cache,
                                     CacheVersionStore versions,
                                     ScopeResolver scopeResolver,
                                     ConditionEvaluator conditions,
                                     OrganizationScopeQueries organizationScopes,
                                     MemberStore memberStore,
                                     AccessRequestStore accessRequests,
                                     AdminActionStore adminActions,
                                     AuthorizationProperties properties,
                                     ObjectMapper objectMapper,
                                     Clock clock) {
        this.tenantTransactions = tenantTransactions;
        this.store = store;
        this.cache = cache;
        this.versions = versions;
        this.scopeResolver = scopeResolver;
        this.conditions = conditions;
        this.organizationScopes = organizationScopes;
        this.memberStore = memberStore;
        this.accessRequests = accessRequests;
        this.adminActions = adminActions;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 角色

    public List<RoleView> listRoles() {
        return tenantTransactions.inTenant(context -> {
            List<RoleView> result = new ArrayList<>();
            for (var template : SystemRoleCatalog.all()) {
                result.add(new RoleView(template.code(), template.name(), "内置系统角色", true,
                        "active", template.permissions()));
            }
            for (var role : store.listRoles()) {
                result.add(new RoleView(role.code(), role.name(), role.description(), false,
                        role.status(), store.rolePermissions(role.id())));
            }
            return result;
        });
    }

    public RoleView createRole(CreateRoleCommand command) {
        validateCode(command.code());
        if (command.name() == null || command.name().isBlank()) {
            throw ApiException.unprocessable("ROLE_NAME_REQUIRED", "角色名称不能为空");
        }
        if (SystemRoleCatalog.exists(command.code())) {
            throw ApiException.conflict("ROLE_CODE_RESERVED", "系统角色码不能被自定义角色占用");
        }
        Set<String> permissions = command.permissions() == null ? Set.of() : command.permissions();
        validatePermissions(permissions);
        return tenantTransactions.inTenant(context -> {
            UUID roleId = UUID.randomUUID();
            try {
                store.insertRole(roleId, command.code(), command.name().trim(),
                        command.description());
                store.replaceRolePermissions(roleId, permissions);
                audit("ROLE_CREATED", "authorization.role", roleId,
                        Map.of("code", command.code(), "permissions", permissions));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ROLE_CODE_TAKEN", "角色码已存在：" + command.code());
            }
            return new RoleView(command.code(), command.name().trim(), command.description(),
                    false, "active", permissions);
        });
    }

    public RoleView replacePermissions(UUID roleId, Set<String> permissions) {
        validatePermissions(permissions);
        return tenantTransactions.inTenant(context -> {
            AuthorizationStore.RoleRow role = store.findRole(roleId).orElseThrow(() ->
                    ApiException.notFound("ROLE_NOT_FOUND", "自定义角色不存在：" + roleId));
            store.replaceRolePermissions(roleId, permissions == null ? Set.of() : permissions);
            audit("ROLE_PERMISSIONS_REPLACED", "authorization.role", roleId,
                    Map.of("code", role.code(), "permissions",
                            permissions == null ? Set.of() : permissions));
            versions.invalidateLocal();
            return new RoleView(role.code(), role.name(), role.description(), false, role.status(),
                    permissions == null ? Set.of() : permissions);
        });
    }

    // ---------------------------------------------------------------- 授权

    public List<AuthorizationStore.AssignmentRow> listAssignments(UUID actorId, int limit) {
        return tenantTransactions.inTenant(context -> store.listAssignments(actorId, limit));
    }

    public AuthorizationStore.AssignmentRow grant(GrantCommand command) {
        ScopeType scopeType = ScopeType.fromWire(command.scopeType());
        if (scopeType == null) {
            throw ApiException.unprocessable("SCOPE_TYPE_INVALID", "未知的范围类型：" + command.scopeType());
        }
        if (scopeType == ScopeType.PROJECT_SPACE || scopeType == ScopeType.RECORD_SHARED) {
            throw ApiException.unprocessable("SCOPE_UNSUPPORTED", "该范围类型尚未实现");
        }
        if (scopeType.requiresScopeId() && command.scopeId() == null) {
            throw ApiException.unprocessable("SCOPE_ID_REQUIRED", "该范围类型必须提供 scopeId");
        }
        if (!scopeType.requiresScopeId() && command.scopeId() != null) {
            throw ApiException.unprocessable("SCOPE_ID_FORBIDDEN", "该范围类型不能携带 scopeId");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw ApiException.unprocessable("REASON_REQUIRED", "授权必须填写依据");
        }
        if (command.actorId() == null) {
            throw ApiException.unprocessable("ACTOR_REQUIRED", "必须指定授权主体");
        }
        OffsetDateTime validFrom = command.validFrom() == null
                ? OffsetDateTime.now(clock) : command.validFrom();
        if (command.validTo() != null && !command.validTo().isAfter(validFrom)) {
            throw ApiException.unprocessable("GRANT_PERIOD_INVALID", "到期时间必须晚于生效时间");
        }
        if (command.validTo() != null) {
            long days = Duration.between(validFrom, command.validTo()).toDays();
            if (days > properties.getMaxTemporaryGrantDays()) {
                throw ApiException.unprocessable("GRANT_TOO_LONG",
                        "临时授权不能超过 " + properties.getMaxTemporaryGrantDays() + " 天");
            }
        }

        return tenantTransactions.inTenant(context -> {
            if (store.findActor(command.actorId()).isEmpty()) {
                throw ApiException.notFound("ACTOR_NOT_FOUND", "授权主体不存在");
            }
            requireRole(command.roleCode());
            boolean highRisk = highRisk(command.roleCode());
            if (highRisk && command.validTo() == null) {
                throw ApiException.unprocessable("TEMPORARY_GRANT_REQUIRED",
                        "高权限角色必须设置到期时间");
            }
            validateScopeTarget(scopeType, command.scopeId());
            validateDelegation(command, scopeType, validFrom);
            boolean emergency = validateHighPrivilegeGrant(command, highRisk);
            validateRoleConflict(command.actorId(), command.roleCode());
            UUID id = UUID.randomUUID();
            store.insertAssignment(id, command.actorId(), command.memberId(), command.roleCode(),
                    scopeType, command.scopeId(), validFrom, command.validTo(),
                    command.reason().trim(), currentActor(), command.delegatedFromId(),
                    command.requestId(), emergency);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("roleCode", command.roleCode());
            detail.put("actorId", command.actorId());
            detail.put("requestId", command.requestId());
            detail.put("reviewRequired", emergency);
            detail.put("emergency", emergency);
            audit("ASSIGNMENT_GRANTED", "authorization.assignment", id, detail);
            invalidate(command.actorId(), context.tenantId());
            return store.findAssignment(id).orElseThrow();
        });
    }

    /**
     * 高权限双人校验：必须引用他人批准的申请；仅租户所有者可紧急直授并强制进入复核。
     *
     * @return 是否为紧急直授（需要 review_required=true）
     */
    private boolean validateHighPrivilegeGrant(GrantCommand command, boolean highRisk) {
        UUID operator = currentActorId();
        if (highRisk && operator != null && command.actorId().equals(operator)) {
            throw ApiException.forbidden("SOD_SELF_APPROVAL", "不能给自己授予高权限");
        }
        if (command.requestId() != null) {
            AccessRequestStore.AccessRequestRow request = accessRequests.find(command.requestId())
                    .orElseThrow(() -> ApiException.notFound("ACCESS_REQUEST_NOT_FOUND",
                            "引用的申请不存在"));
            if (!"approved".equals(request.state())) {
                throw ApiException.unprocessable("ACCESS_REQUEST_REQUIRED",
                        "高权限授予必须引用已批准的申请");
            }
            if (!request.requestedForActorId().equals(command.actorId())
                    || !request.roleCode().equals(command.roleCode())) {
                throw ApiException.unprocessable("ACCESS_REQUEST_MISMATCH",
                        "申请主体或角色与本次授予不一致");
            }
            if (highRisk && operator != null && request.requesterActorId().equals(operator)) {
                throw ApiException.forbidden("SOD_SELF_APPROVAL",
                        "不能批准并授予自己的高权限申请");
            }
            if (request.validTo() != null
                    && (command.validTo() == null
                        || command.validTo().isAfter(request.validTo()))) {
                throw ApiException.unprocessable("GRANT_TOO_LONG", "授予到期不能晚于申请");
            }
            return false;
        }
        if (!highRisk) {
            return false;
        }
        boolean owner = operator != null && store.findActorMember(operator)
                .map(AuthorizationStore.ActorMemberRow::owner).orElse(false);
        if (!owner) {
            throw ApiException.unprocessable("ACCESS_REQUEST_REQUIRED",
                    "高权限授予必须引用他人批准的申请");
        }
        return true;
    }

    /** 高权限按实际权限集合判定，自定义角色含高权限码同样受限时与双人规则。 */
    private boolean highRisk(String roleCode) {
        if (SystemRoleCatalog.highRisk(roleCode)) {
            return true;
        }
        Set<String> permissions = store.customRolePermissions(Set.of(roleCode))
                .getOrDefault(roleCode, Set.of());
        return permissions.stream()
                .anyMatch(SodRuleCatalog.highPrivilegePermissions()::contains);
    }

    private void validateRoleConflict(UUID actorId, String roleCode) {
        Set<String> roles = new LinkedHashSet<>(store.activeRoleCodes(actorId,
                OffsetDateTime.now(clock)));
        roles.add(roleCode);
        SodRuleCatalog.roleConflict(roles).ifPresent(conflict -> {
            throw ApiException.unprocessable("SOD_ROLE_CONFLICT",
                    "角色组合触发职责分离：" + conflict.detail());
        });
    }

    public void revokeAssignment(UUID assignmentId) {
        tenantTransactions.inTenant(context -> {
            AuthorizationStore.AssignmentRow assignment = store.findAssignment(assignmentId)
                    .orElseThrow(() -> ApiException.notFound("ASSIGNMENT_NOT_FOUND", "授权不存在"));
            if (store.revokeAssignment(assignmentId, currentActor()) == 0) {
                throw ApiException.conflict("ASSIGNMENT_ALREADY_REVOKED", "授权已撤销");
            }
            audit("ASSIGNMENT_REVOKED", "authorization.assignment", assignmentId,
                    Map.of("actorId", assignment.actorId(), "roleCode", assignment.roleCode()));
            invalidate(assignment.actorId(), context.tenantId());
            return null;
        });
    }

    // ---------------------------------------------------------------- 显式拒绝

    public List<AuthorizationStore.DenyRow> listDenies(int limit) {
        return tenantTransactions.inTenant(context -> store.listDenies(limit));
    }

    public AuthorizationStore.DenyRow addDeny(DenyCommand command) {
        if (!PermissionCatalog.exists(command.permissionCode())) {
            throw ApiException.unprocessable("PERMISSION_UNKNOWN", "未知权限码");
        }
        if (command.actorId() == null && (command.roleCode() == null || command.roleCode().isBlank())) {
            throw ApiException.unprocessable("DENY_SUBJECT_REQUIRED", "显式拒绝必须指定主体或角色");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw ApiException.unprocessable("REASON_REQUIRED", "显式拒绝必须填写依据");
        }
        ScopeType scopeType = command.scopeType() == null
                ? null : ScopeType.fromWire(command.scopeType());
        if (command.scopeType() != null && scopeType == null) {
            throw ApiException.unprocessable("SCOPE_TYPE_INVALID", "未知的范围类型");
        }
        if (scopeType != null && scopeType.requiresScopeId() && command.scopeId() == null) {
            throw ApiException.unprocessable("SCOPE_ID_REQUIRED", "该范围类型必须提供 scopeId");
        }
        return tenantTransactions.inTenant(context -> {
            UUID id = UUID.randomUUID();
            try {
                store.insertDeny(id, command.actorId(), command.roleCode(),
                        command.permissionCode(), command.resourceType(), command.resourceId(),
                        scopeType, command.scopeId(), command.reason().trim(),
                        command.expiresAt(), currentActor());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DENY_EXISTS", "相同的长期显式拒绝已存在");
            }
            audit("DENY_ADDED", "authorization.deny", id,
                    Map.of("permissionCode", command.permissionCode(),
                            "actorId", String.valueOf(command.actorId()),
                            "roleCode", String.valueOf(command.roleCode())));
            if (command.actorId() != null) {
                invalidate(command.actorId(), context.tenantId());
            } else {
                versions.invalidateLocal();
            }
            return store.findDeny(id).orElseThrow(() ->
                    new IllegalStateException("显式拒绝写入后不可见"));
        });
    }

    public void removeDeny(UUID denyId) {
        tenantTransactions.inTenant(context -> {
            if (store.deleteDeny(denyId) == 0) {
                throw ApiException.notFound("DENY_NOT_FOUND", "显式拒绝不存在");
            }
            audit("DENY_REMOVED", "authorization.deny", denyId, Map.of());
            versions.invalidateLocal();
            return null;
        });
    }

    // ---------------------------------------------------------------- 字段与条件策略

    public List<AuthorizationStore.FieldPolicyRow> listFieldPolicies() {
        return tenantTransactions.inTenant(context -> store.listFieldPolicies());
    }

    public void putFieldPolicy(FieldPolicyCommand command) {
        if (!"allow".equals(command.effect()) && !"deny".equals(command.effect())) {
            throw ApiException.unprocessable("EFFECT_INVALID", "effect 只能是 allow 或 deny");
        }
        if (command.roleCode() == null || command.resourceType() == null
                || command.fieldName() == null) {
            throw ApiException.unprocessable("FIELD_POLICY_INCOMPLETE", "角色、资源与字段名不能为空");
        }
        tenantTransactions.inTenant(context -> {
            store.upsertFieldPolicy(command.roleCode(), command.resourceType(),
                    command.fieldName(), command.canView(), command.canEdit(), command.canExport(),
                    command.canUseAi(), command.effect());
            audit("FIELD_POLICY_UPDATED", "authorization.field_policy", null,
                    Map.of("roleCode", command.roleCode(),
                            "resourceType", command.resourceType(),
                            "fieldName", command.fieldName()));
            versions.invalidateLocal();
            return null;
        });
    }

    public List<AuthorizationStore.ConditionRow> listConditionPolicies() {
        return tenantTransactions.inTenant(context -> store.listConditionPolicies());
    }

    public void putConditionPolicy(ConditionCommand command) {
        if (!PermissionCatalog.exists(command.permissionCode())) {
            throw ApiException.unprocessable("PERMISSION_UNKNOWN", "未知权限码");
        }
        if (!"allow".equals(command.effect()) && !"deny".equals(command.effect())) {
            throw ApiException.unprocessable("EFFECT_INVALID", "effect 只能是 allow 或 deny");
        }
        if (command.conditionJson() == null || command.conditionJson().isBlank()) {
            throw ApiException.unprocessable("CONDITION_REQUIRED", "条件不能为空");
        }
        if (!conditions.isParseable(command.conditionJson())) {
            throw ApiException.unprocessable("CONDITION_INVALID", "条件格式无法解析");
        }
        tenantTransactions.inTenant(context -> {
            store.upsertConditionPolicy(command.permissionCode(), command.name(),
                    command.conditionJson(), command.effect(), command.priority());
            audit("CONDITION_POLICY_UPDATED", "authorization.condition_policy", null,
                    Map.of("permissionCode", command.permissionCode(), "name", command.name()));
            versions.invalidateLocal();
            return null;
        });
    }

    // ---------------------------------------------------------------- 审计与冲突清单

    public List<AdminActionStore.AdminActionRow> listAdminActions(int limit) {
        return tenantTransactions.inTenant(context -> adminActions.list(limit));
    }

    /** 当前生效角色组合触发静态互斥的主体，供复核整改。 */
    public List<SeparationConflictView> separationConflicts() {
        return tenantTransactions.inTenant(context -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            Map<UUID, Set<String>> rolesByActor = new LinkedHashMap<>();
            for (AuthorizationStore.AssignmentRow assignment
                    : store.listActiveAssignments(now, 5000)) {
                rolesByActor.computeIfAbsent(assignment.actorId(), key -> new LinkedHashSet<>())
                        .add(assignment.roleCode());
            }
            List<SeparationConflictView> conflicts = new ArrayList<>();
            for (Map.Entry<UUID, Set<String>> entry : rolesByActor.entrySet()) {
                SodRuleCatalog.roleConflict(entry.getValue()).ifPresent(conflict ->
                        conflicts.add(new SeparationConflictView(entry.getKey(),
                                entry.getValue(), conflict.group(), conflict.detail())));
            }
            return conflicts;
        });
    }

    // ---------------------------------------------------------------- 模拟

    public SimulationResult simulate(SimulationCommand command) {
        return tenantTransactions.inTenant(context -> {
            ActorContext actor = ActorContextHolder.current().orElseThrow(() ->
                    new ApiException(401, "SESSION_REQUIRED", "模拟需要已认证主体"));
            TenantMember member = actor.memberId() == null ? null
                    : memberStore.findById(actor.memberId()).orElse(null);
            EmploymentScope employment = member == null || member.personId() == null ? null
                    : organizationScopes.scopeOfPerson(member.personId(),
                    LocalDate.now(clock)).orElse(null);
            List<RoleGrant> grants = new ArrayList<>();
            for (SimulatedGrant grant : command.grants() == null
                    ? List.<SimulatedGrant>of() : command.grants()) {
                ScopeType type = ScopeType.fromWire(grant.scopeType());
                if (type == null) {
                    continue;
                }
                grants.add(RoleGrant.derived(grant.roleCode(), type, grant.scopeId(), "SIMULATED"));
            }
            Set<String> roleCodes = new LinkedHashSet<>();
            for (RoleGrant grant : grants) {
                roleCodes.add(grant.roleCode());
            }
            Map<String, Set<String>> custom = store.customRolePermissions(roleCodes);
            List<AccessDecision> decisions = new ArrayList<>();
            OffsetDateTime now = OffsetDateTime.now(clock);
            for (SimulationCheck check : command.checks() == null
                    ? List.<SimulationCheck>of() : command.checks()) {
                decisions.add(simulateCheck(actor, member, employment, check, grants, custom,
                        command.denies(), now));
            }
            return new SimulationResult(decisions);
        });
    }

    private AccessDecision simulateCheck(ActorContext actor, TenantMember member,
                                         EmploymentScope employment, SimulationCheck check,
                                         List<RoleGrant> grants,
                                         Map<String, Set<String>> custom,
                                         List<SimulatedDeny> denies, OffsetDateTime now) {
        if (!PermissionCatalog.exists(check.permission())) {
            return AccessDecision.deny(DecisionReason.PERMISSION_UNKNOWN, 0L, now,
                    List.of("SIMULATED", "UNKNOWN_PERMISSION"));
        }
        if (denies != null) {
            for (SimulatedDeny deny : denies) {
                if (deny.permissionCode() != null
                        && deny.permissionCode().equals(check.permission())
                        && (deny.resourceType() == null
                            || deny.resourceType().equals(check.resourceType()))
                        && (deny.resourceId() == null || deny.resourceId().equals(check.resourceId()))) {
                    return AccessDecision.deny(DecisionReason.EXPLICIT_DENY, 0L, now,
                            List.of("SIMULATED", "DENY:SIMULATED"));
                }
            }
        }
        List<RoleGrant> matching = grants.stream()
                .filter(grant -> permissionsOf(grant.roleCode(), custom)
                        .contains(check.permission()))
                .toList();
        if (matching.isEmpty()) {
            return AccessDecision.deny(DecisionReason.NO_ALLOW, 0L, now,
                    List.of("SIMULATED", "NO_MATCHING_GRANT"));
        }
        AccessScope scope = AccessScope.none();
        boolean any = false;
        for (RoleGrant grant : matching) {
            ScopeResolver.ScopeResolution resolution = scopeResolver.resolve(grant.scopeType(),
                    grant.scopeId(), member == null ? null : member.personId(), employment);
            if (!resolution.supported()) {
                continue;
            }
            scope = scope.merge(resolution.scope());
            any = true;
        }
        if (!any) {
            return AccessDecision.deny(DecisionReason.SCOPE_MISS, 0L, now,
                    List.of("SIMULATED", "EMPTY_SCOPE"));
        }
        AccessRequest request = new AccessRequest(actor.tenantId(), actor.actorId(),
                check.permission(), check.resourceType(), check.resourceId(), check.attributes());
        if (check.resourceId() != null) {
            boolean visible = AuthorizationService.resourceInScope(request, scope,
                    actor.actorId(), member == null ? null : member.personId());
            if (!visible) {
                UUID personId = AuthorizationService.asUuid(check.attributes().get("personId"));
                visible = personId != null
                        && organizationScopes.primaryUnitOfPerson(personId, LocalDate.now(clock))
                        .map(scope::includesOrgUnit).orElse(false);
            }
            if (!visible) {
                return AccessDecision.deny(DecisionReason.SCOPE_MISS, 0L, now,
                        List.of("SIMULATED", "RESOURCE_OUT_OF_SCOPE"));
            }
        }
        for (AuthorizationStore.ConditionRow condition
                : store.conditionPolicies(check.permission())) {
            boolean passed = conditions.passes(condition.condition(), check.attributes());
            if ("allow".equals(condition.effect()) && !passed) {
                return AccessDecision.deny(DecisionReason.CONDITION_FAILED, 0L, now,
                        List.of("SIMULATED", "CONDITION:" + condition.name()));
            }
            if ("deny".equals(condition.effect()) && passed) {
                return AccessDecision.deny(DecisionReason.CONDITION_FAILED, 0L, now,
                        List.of("SIMULATED", "CONDITION:" + condition.name()));
            }
        }
        return new AccessDecision(true, DecisionReason.ALLOWED,
                matching.stream().map(grant -> "SIMULATED:" + grant.roleCode()).toList(),
                scope, Map.of(), 0L, now, false);
    }

    private Set<String> permissionsOf(String roleCode, Map<String, Set<String>> custom) {
        return SystemRoleCatalog.exists(roleCode)
                ? SystemRoleCatalog.permissions(roleCode)
                : custom.getOrDefault(roleCode, Set.of());
    }

    // ---------------------------------------------------------------- 校验

    private void validateScopeTarget(ScopeType scopeType, UUID scopeId) {
        if (scopeType == ScopeType.ORG_UNIT || scopeType == ScopeType.ORG_UNIT_SUBTREE
                || scopeType == ScopeType.DELEGATED) {
            if (!organizationScopes.unitExists(scopeId)) {
                throw ApiException.notFound("ORG_UNIT_NOT_FOUND", "部门不存在：" + scopeId);
            }
        }
        if (scopeType == ScopeType.LEGAL_ENTITY
                && !organizationScopes.legalEntityExists(scopeId)) {
            throw ApiException.notFound("LEGAL_ENTITY_NOT_FOUND", "法人不存在：" + scopeId);
        }
    }

    private void validateDelegation(GrantCommand command, ScopeType scopeType,
                                    OffsetDateTime validFrom) {
        if (command.delegatedFromId() == null) {
            return;
        }
        AuthorizationStore.AssignmentRow source = store.findAssignment(command.delegatedFromId())
                .orElseThrow(() -> ApiException.notFound("SOURCE_ASSIGNMENT_NOT_FOUND",
                        "委托源授权不存在"));
        if (source.revokedAt() != null) {
            throw ApiException.conflict("SOURCE_ASSIGNMENT_REVOKED", "委托源授权已撤销");
        }
        if (!source.roleCode().equals(command.roleCode())) {
            throw ApiException.unprocessable("DELEGATION_ROLE_MISMATCH", "委托角色必须与源授权一致");
        }
        boolean broader = source.scopeType() != ScopeType.TENANT
                && (source.scopeType() != scopeType
                    || !java.util.Objects.equals(source.scopeId(), command.scopeId()));
        if (broader) {
            throw ApiException.unprocessable("DELEGATION_SCOPE_BROADER", "委托范围不能大于源授权");
        }
        if (source.validTo() != null
                && (command.validTo() == null || command.validTo().isAfter(source.validTo()))) {
            throw ApiException.unprocessable("DELEGATION_TOO_LONG", "委托到期不能晚于源授权");
        }
        long days = command.validTo() == null ? 0
                : Duration.between(validFrom, command.validTo()).toDays();
        if (days > properties.getMaxDelegationDays()) {
            throw ApiException.unprocessable("DELEGATION_TOO_LONG",
                    "委托不能超过 " + properties.getMaxDelegationDays() + " 天");
        }
    }

    private void requireRole(String roleCode) {
        if (SystemRoleCatalog.exists(roleCode)) {
            return;
        }
        Optional<AuthorizationStore.RoleRow> custom = store.findRoleByCode(roleCode);
        if (custom.isEmpty() || !"active".equals(custom.get().status())) {
            throw ApiException.notFound("ROLE_NOT_FOUND", "角色不存在或已停用：" + roleCode);
        }
    }

    private void validatePermissions(Set<String> permissions) {
        for (String code : permissions) {
            if (!PermissionCatalog.exists(code)) {
                throw ApiException.unprocessable("PERMISSION_UNKNOWN", "未知权限码：" + code);
            }
        }
    }

    private void validateCode(String code) {
        if (code == null || !code.matches("^[a-z][a-z0-9-]{2,63}$")) {
            throw ApiException.unprocessable("ROLE_CODE_INVALID",
                    "角色码只能是小写字母、数字和连字符，长度 3-64");
        }
    }

    private void audit(String action, String targetType, UUID targetId,
                       Map<String, Object> detail) {
        try {
            adminActions.insert(action, targetType, targetId,
                    objectMapper.writeValueAsString(detail));
        } catch (RuntimeException e) {
            throw new IllegalStateException("管理员审计写入失败", e);
        }
    }

    private UUID currentActorId() {
        return ActorContextHolder.current().map(ActorContext::actorId).orElse(null);
    }

    private String currentActor() {
        return ActorContextHolder.current()
                .map(actor -> actor.actorId().toString())
                .orElse("system");
    }

    private void invalidate(UUID actorId, UUID tenantId) {
        cache.invalidateActor(actorId, tenantId);
        versions.invalidateLocal();
    }
}
