package com.acme.platform.authorization;

import com.acme.platform.platform.ActorContextHolder;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 权限申请与审批：申请、批准、驳回、撤销。
 *
 * <p>批准时复用 {@link AuthorizationAdminService#grant} 的内部路径，因此双人校验、
 * 高权限限时、静态冲突预检与管理员审计都只有一套实现。
 */
@Service
public class AccessRequestService {

    public record CreateCommand(UUID requestedForActorId, String roleCode, String scopeType,
                                UUID scopeId, OffsetDateTime validTo, String reason) {
    }

    private final TenantTransactions tenantTransactions;
    private final AccessRequestStore store;
    private final AuthorizationStore assignments;
    private final AuthorizationAdminService adminService;
    private final AuthorizationGuard guard;
    private final AuthorizationService authorization;
    private final AdminActionStore adminActions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AccessRequestService(TenantTransactions tenantTransactions,
                                AccessRequestStore store,
                                AuthorizationStore assignments,
                                AuthorizationAdminService adminService,
                                AuthorizationGuard guard,
                                AuthorizationService authorization,
                                AdminActionStore adminActions,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.tenantTransactions = tenantTransactions;
        this.store = store;
        this.assignments = assignments;
        this.adminService = adminService;
        this.guard = guard;
        this.authorization = authorization;
        this.adminActions = adminActions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public AccessRequestStore.AccessRequestRow create(CreateCommand command) {
        UUID requester = currentActorId();
        UUID requestedFor = command.requestedForActorId() == null
                ? requester : command.requestedForActorId();
        ScopeType scopeType = ScopeType.fromWire(command.scopeType());
        if (scopeType == null) {
            throw ApiException.unprocessable("SCOPE_TYPE_INVALID",
                    "未知的范围类型：" + command.scopeType());
        }
        if (scopeType.requiresScopeId() && command.scopeId() == null) {
            throw ApiException.unprocessable("SCOPE_ID_REQUIRED", "该范围类型必须提供 scopeId");
        }
        if (!scopeType.requiresScopeId() && command.scopeId() != null) {
            throw ApiException.unprocessable("SCOPE_ID_FORBIDDEN", "该范围类型不能携带 scopeId");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw ApiException.unprocessable("REASON_REQUIRED", "申请必须填写理由");
        }
        if (command.validTo() != null && !command.validTo().isAfter(OffsetDateTime.now(clock))) {
            throw ApiException.unprocessable("REQUEST_PERIOD_INVALID", "到期时间必须晚于当前时间");
        }
        return tenantTransactions.inTenant(context -> {
            if (assignments.findActor(requester).isEmpty()
                    || assignments.findActor(requestedFor).isEmpty()) {
                throw ApiException.notFound("ACTOR_NOT_FOUND", "申请主体不存在");
            }
            requireRole(command.roleCode());
            UUID id = UUID.randomUUID();
            try {
                store.insert(id, requester, requestedFor, command.roleCode(), scopeType,
                        command.scopeId(), command.validTo(), command.reason().trim());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACCESS_REQUEST_EXISTS",
                        "同一主体、角色与范围的待处理申请已存在");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("requestedForActorId", requestedFor);
            detail.put("roleCode", command.roleCode());
            detail.put("scopeType", scopeType.wire());
            detail.put("scopeId", command.scopeId());
            audit("ACCESS_REQUEST_CREATED", id, detail);
            return store.find(id).orElseThrow();
        });
    }

    public List<AccessRequestStore.AccessRequestRow> list(String state, int limit) {
        UUID actor = currentActorId();
        boolean approver = authorization.holdsPermission("authorization.access.approve");
        if (!approver) {
            guard.require("authorization.access.request", "authorization.access_request", null,
                    Map.of());
        }
        return tenantTransactions.inTenant(context ->
                store.list(state, approver ? null : actor, limit));
    }

    public AccessRequestStore.AccessRequestRow approve(UUID requestId, String note) {
        return tenantTransactions.inTenant(context -> {
            AccessRequestStore.AccessRequestRow request = pendingRequest(requestId);
            UUID operator = currentActorId();
            if (request.requesterActorId().equals(operator)
                    || request.requestedForActorId().equals(operator)) {
                throw ApiException.forbidden("SOD_SELF_APPROVAL", "不能批准自己的权限申请");
            }
            // 决策层同样校验：requestedBy 只能由服务端从申请记录派生
            guard.require("authorization.access.approve", "authorization.access_request",
                    null, Map.of("requestedBy", request.requesterActorId()));

            store.decide(requestId, "approved", operator, note);
            AuthorizationStore.AssignmentRow assignment = adminService.grant(
                    new AuthorizationAdminService.GrantCommand(
                            request.requestedForActorId(), null, request.roleCode(),
                            request.scopeType().wire(), request.scopeId(), null,
                            request.validTo(), request.reason(), null, requestId));
            store.attachAssignment(requestId, assignment.id());
            audit("ACCESS_REQUEST_APPROVED", requestId,
                    Map.of("assignmentId", assignment.id()));
            return store.find(requestId).orElseThrow();
        });
    }

    public AccessRequestStore.AccessRequestRow reject(UUID requestId, String note) {
        return tenantTransactions.inTenant(context -> {
            AccessRequestStore.AccessRequestRow request = pendingRequest(requestId);
            UUID operator = currentActorId();
            if (request.requesterActorId().equals(operator)
                    || request.requestedForActorId().equals(operator)) {
                throw ApiException.forbidden("SOD_SELF_APPROVAL", "不能驳回自己的权限申请");
            }
            guard.require("authorization.access.approve", "authorization.access_request",
                    null, Map.of("requestedBy", request.requesterActorId()));
            store.decide(requestId, "rejected", operator, note);
            audit("ACCESS_REQUEST_REJECTED", requestId, Map.of());
            return store.find(requestId).orElseThrow();
        });
    }

    public AccessRequestStore.AccessRequestRow cancel(UUID requestId) {
        return tenantTransactions.inTenant(context -> {
            AccessRequestStore.AccessRequestRow request = pendingRequest(requestId);
            if (!request.requesterActorId().equals(currentActorId())) {
                throw ApiException.forbidden("REQUEST_NOT_OWNER", "只有申请人可以撤回申请");
            }
            store.decide(requestId, "cancelled", currentActorId(), null);
            audit("ACCESS_REQUEST_CANCELLED", requestId, Map.of());
            return store.find(requestId).orElseThrow();
        });
    }

    private AccessRequestStore.AccessRequestRow pendingRequest(UUID requestId) {
        AccessRequestStore.AccessRequestRow request = store.find(requestId)
                .orElseThrow(() -> ApiException.notFound("ACCESS_REQUEST_NOT_FOUND", "申请不存在"));
        if (!"pending".equals(request.state())) {
            throw ApiException.conflict("ACCESS_REQUEST_DECIDED", "申请已处理");
        }
        return request;
    }

    private void requireRole(String roleCode) {
        if (SystemRoleCatalog.exists(roleCode)) {
            return;
        }
        Optional<AuthorizationStore.RoleRow> custom = assignments.findRoleByCode(roleCode);
        if (custom.isEmpty() || !"active".equals(custom.get().status())) {
            throw ApiException.notFound("ROLE_NOT_FOUND", "角色不存在或已停用：" + roleCode);
        }
    }

    private UUID currentActorId() {
        return ActorContextHolder.current().map(actor -> actor.actorId())
                .orElseThrow(() -> new ApiException(401, "SESSION_REQUIRED", "申请需要已认证主体"));
    }

    private void audit(String action, UUID targetId, Map<String, Object> detail) {
        adminActions.insert(action, "authorization.access_request", targetId,
                objectMapper.writeValueAsString(detail));
    }
}
