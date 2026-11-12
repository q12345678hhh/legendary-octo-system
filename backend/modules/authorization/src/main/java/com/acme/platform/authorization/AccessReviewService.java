package com.acme.platform.authorization;

import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.ActorContextHolder;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 季度访问复核：生成、确认、撤销、逾期升级与自动暂停。
 *
 * <p>Worker 只通过非 RLS 的 {@code platform_access_review_due} 发现租户；
 * 每个租户的业务写入都在自己的租户事务里完成，审计与业务变更同事务提交。
 */
@Service
public class AccessReviewService {

    public record ReviewDetail(AccessReviewStore.ReviewRow review,
                               List<AccessReviewStore.ItemRow> items) {
    }

    private final TenantTransactions tenantTransactions;
    private final AccessReviewStore store;
    private final AuthorizationStore assignments;
    private final OrganizationScopeQueries organizationScopes;
    private final AuthorizationGuard guard;
    private final AuthorizationService authorization;
    private final CacheVersionStore versions;
    private final AdminActionStore adminActions;
    private final AuthorizationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AccessReviewService(TenantTransactions tenantTransactions,
                               AccessReviewStore store,
                               AuthorizationStore assignments,
                               OrganizationScopeQueries organizationScopes,
                               AuthorizationGuard guard,
                               AuthorizationService authorization,
                               CacheVersionStore versions,
                               AdminActionStore adminActions,
                               AuthorizationProperties properties,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.tenantTransactions = tenantTransactions;
        this.store = store;
        this.assignments = assignments;
        this.organizationScopes = organizationScopes;
        this.guard = guard;
        this.authorization = authorization;
        this.versions = versions;
        this.adminActions = adminActions;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 生成

    /** Worker 入口：认领到期租户并逐租户生成；返回生成的条目数。 */
    public int generateDue(int batchSize) {
        List<UUID> tenants = store.claimDueTenants(batchSize);
        int created = 0;
        for (UUID tenantId : tenants) {
            created += tenantTransactions.inTenant(
                    new RequestContext(tenantId, null, "access-review",
                            UUID.randomUUID().toString().replace("-", "")),
                    context -> generateForTenant(context.tenantId()));
        }
        return created;
    }

    /** 手动补跑入口：Worker 停摆时由 review.manage 触发。 */
    public ReviewDetail generateCurrentTenant() {
        guard.require("authorization.review.manage", "authorization.access_review", null,
                Map.of());
        return tenantTransactions.inTenant(context -> {
            generateForTenant(context.tenantId());
            LocalDate start = quarterStart(LocalDate.now(clock));
            return store.findReviewByPeriod(start)
                    .map(review -> new ReviewDetail(review, store.items(review.id())))
                    .orElseThrow(() -> new IllegalStateException("复核生成后不可见"));
        });
    }

    private int generateForTenant(UUID tenantId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);
        LocalDate periodStart = quarterStart(today);
        LocalDate periodEnd = periodStart.plusMonths(3);
        Optional<UUID> created = store.insertReviewIfAbsent(tenantId, periodStart, periodEnd,
                now.plusDays(properties.getReviewWindowDays()));
        if (created.isEmpty()) {
            return 0;
        }
        UUID reviewId = created.get();
        List<AuthorizationStore.AssignmentRow> active =
                assignments.listActiveAssignments(now, 5000);
        Set<String> roleCodes = new LinkedHashSet<>();
        for (AuthorizationStore.AssignmentRow assignment : active) {
            roleCodes.add(assignment.roleCode());
        }
        Map<String, Set<String>> custom = assignments.customRolePermissions(roleCodes);
        int count = 0;
        for (AuthorizationStore.AssignmentRow assignment : active) {
            boolean highPrivilege = SystemRoleCatalog.highRisk(assignment.roleCode())
                    || custom.getOrDefault(assignment.roleCode(), Set.of()).stream()
                    .anyMatch(SodRuleCatalog.highPrivilegePermissions()::contains);
            if (!highPrivilege && !assignment.reviewRequired()) {
                continue;
            }
            UUID reviewer = resolveReviewer(assignment.actorId(), today, now).orElse(null);
            store.insertItemIfAbsent(reviewId, assignment.id(), assignment.actorId(), reviewer);
            count++;
        }
        if (count == 0) {
            store.closeReview(reviewId, now);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("periodStart", periodStart.toString());
        detail.put("periodEnd", periodEnd.toString());
        detail.put("items", count);
        audit("ACCESS_REVIEW_GENERATED", reviewId, detail);
        return count;
    }

    /**
     * 复核人：直接主管 → 租户所有者 → 安全管理员。
     *
     * <p>所有者本人的高权限由安全管理员复核；无法解析时留空，交由 review.manage 处理，
     * 但逾期自动暂停照常执行（fail-closed）。
     */
    private Optional<UUID> resolveReviewer(UUID subjectActorId, LocalDate today,
                                           OffsetDateTime now) {
        Optional<AuthorizationStore.ActorMemberRow> subject =
                assignments.findActorMember(subjectActorId);
        UUID personId = subject.map(AuthorizationStore.ActorMemberRow::personId).orElse(null);
        boolean owner = subject.map(AuthorizationStore.ActorMemberRow::owner).orElse(false);

        Optional<UUID> manager = organizationScopes.managerActorOf(personId, today)
                .filter(actor -> !actor.equals(subjectActorId));
        if (manager.isPresent() && !owner) {
            return manager;
        }

        List<UUID> owners = organizationScopes.ownerActorIds(today).stream()
                .filter(actor -> !actor.equals(subjectActorId))
                .toList();
        if (owner) {
            Optional<UUID> securityAdmin = assignments
                    .activeSecurityAdminActors(subjectActorId, now).stream().findFirst();
            if (securityAdmin.isPresent()) {
                return securityAdmin;
            }
            return owners.stream().findFirst();
        }
        if (!owners.isEmpty()) {
            return Optional.of(owners.getFirst());
        }
        if (manager.isPresent()) {
            return manager;
        }
        return assignments.activeSecurityAdminActors(subjectActorId, now).stream().findFirst();
    }

    // ---------------------------------------------------------------- 查询

    public List<AccessReviewStore.ReviewRow> list(String state, int limit) {
        boolean manage = authorization.holdsPermission("authorization.review.manage");
        if (!manage) {
            guard.require("authorization.review.read", "authorization.access_review", null,
                    Map.of());
        }
        UUID reviewer = manage ? null : currentActorId();
        return tenantTransactions.inTenant(context -> store.listReviews(state, reviewer, limit));
    }

    public ReviewDetail detail(UUID reviewId) {
        boolean manage = authorization.holdsPermission("authorization.review.manage");
        if (!manage) {
            guard.require("authorization.review.read", "authorization.access_review", null,
                    Map.of());
        }
        return tenantTransactions.inTenant(context -> {
            AccessReviewStore.ReviewRow review = store.findReview(reviewId)
                    .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "复核不存在"));
            List<AccessReviewStore.ItemRow> items = store.items(reviewId);
            if (!manage) {
                UUID actor = currentActorId();
                items = items.stream()
                        .filter(item -> actor.equals(item.reviewerActorId()))
                        .toList();
                if (items.isEmpty()) {
                    throw ApiException.forbidden("REVIEW_NOT_REVIEWER", "你不是该复核的复核人");
                }
            }
            return new ReviewDetail(review, items);
        });
    }

    // ---------------------------------------------------------------- 决策

    public AccessReviewStore.ItemRow confirm(UUID reviewId, UUID itemId, String note) {
        return tenantTransactions.inTenant(context -> {
            AccessReviewStore.ItemRow item = authorizeItem(reviewId, itemId);
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (store.decideItem(reviewId, itemId, "confirmed", now, note) == 0) {
                throw ApiException.conflict("REVIEW_ALREADY_DECIDED", "复核条目已处理");
            }
            audit("ACCESS_REVIEW_CONFIRMED", reviewId,
                    Map.of("itemId", itemId, "assignmentId", item.assignmentId()));
            closeIfComplete(reviewId, now);
            return store.findItem(reviewId, itemId).orElseThrow();
        });
    }

    public AccessReviewStore.ItemRow revoke(UUID reviewId, UUID itemId, String note) {
        return tenantTransactions.inTenant(context -> {
            AccessReviewStore.ItemRow item = authorizeItem(reviewId, itemId);
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (store.decideItem(reviewId, itemId, "revoked", now, note) == 0) {
                throw ApiException.conflict("REVIEW_ALREADY_DECIDED", "复核条目已处理");
            }
            assignments.revokeAssignment(item.assignmentId(), "access-review:" + reviewId);
            versions.invalidateLocal();
            audit("ACCESS_REVIEW_REVOKED", reviewId,
                    Map.of("itemId", itemId, "assignmentId", item.assignmentId()));
            closeIfComplete(reviewId, now);
            return store.findItem(reviewId, itemId).orElseThrow();
        });
    }

    public AccessReviewStore.ReviewRow close(UUID reviewId) {
        return tenantTransactions.inTenant(context -> {
            guard.require("authorization.review.manage", "authorization.access_review", null,
                    Map.of());
            store.findReview(reviewId)
                    .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "复核不存在"));
            if (store.countPendingItems(reviewId) > 0) {
                throw ApiException.conflict("REVIEW_ITEMS_PENDING",
                        "仍有未确认的复核条目，不能直接关闭");
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            store.closeReview(reviewId, now);
            audit("ACCESS_REVIEW_CLOSED", reviewId, Map.of());
            return store.findReview(reviewId).orElseThrow();
        });
    }

    private AccessReviewStore.ItemRow authorizeItem(UUID reviewId, UUID itemId) {
        store.findReview(reviewId)
                .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "复核不存在"));
        AccessReviewStore.ItemRow item = store.findItem(reviewId, itemId)
                .orElseThrow(() -> ApiException.notFound("REVIEW_ITEM_NOT_FOUND", "复核条目不存在"));
        UUID operator = currentActorId();
        if (operator.equals(item.subjectActorId())) {
            throw ApiException.forbidden("REVIEW_NOT_REVIEWER", "不能复核自己的授权");
        }
        boolean reviewer = operator.equals(item.reviewerActorId());
        boolean manage = guard.decide("authorization.review.manage",
                "authorization.access_review", null, Map.of()).allowed();
        if (reviewer) {
            guard.require("authorization.review.decide", "authorization.access_review_item",
                    null, Map.of("subjectActorId", item.subjectActorId()));
        } else if (manage) {
            guard.require("authorization.review.manage", "authorization.access_review", null,
                    Map.of());
        } else {
            throw ApiException.forbidden("REVIEW_NOT_REVIEWER", "你不是该条目的复核人");
        }
        if (!"pending".equals(item.state())) {
            throw ApiException.conflict("REVIEW_ALREADY_DECIDED", "复核条目已处理");
        }
        return item;
    }

    private void closeIfComplete(UUID reviewId, OffsetDateTime now) {
        if (store.countPendingItems(reviewId) == 0) {
            store.closeReview(reviewId, now);
        }
    }

    // ---------------------------------------------------------------- 逾期

    /** Worker 入口：到期先升级，宽限期后自动暂停未确认授权。 */
    public int processOverdue(int batchSize) {
        List<UUID> tenants = store.claimSweepTenants(batchSize);
        int revoked = 0;
        for (UUID tenantId : tenants) {
            revoked += tenantTransactions.inTenant(
                    new RequestContext(tenantId, null, "access-review",
                            UUID.randomUUID().toString().replace("-", "")),
                    context -> processOverdueForTenant());
        }
        return revoked;
    }

    private int processOverdueForTenant() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<UUID> escalated = store.markOverdue(now);
        for (UUID reviewId : escalated) {
            audit("ACCESS_REVIEW_ESCALATED", reviewId, Map.of());
        }

        OffsetDateTime cutoff = now.minusDays(properties.getReviewGraceDays());
        Set<UUID> touched = new LinkedHashSet<>();
        int revoked = 0;
        for (AccessReviewStore.ItemRow item : store.pendingItemsPastGrace(cutoff)) {
            if (store.decideItem(item.reviewId(), item.id(), "revoked", now,
                    "逾期未确认自动暂停") == 0) {
                continue;
            }
            assignments.revokeAssignment(item.assignmentId(),
                    "access-review:" + item.reviewId());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("itemId", item.id());
            detail.put("assignmentId", item.assignmentId());
            detail.put("subjectActorId", item.subjectActorId());
            audit("ACCESS_REVIEW_AUTO_REVOKED", item.reviewId(), detail);
            touched.add(item.reviewId());
            revoked++;
        }
        if (!escalated.isEmpty() || !touched.isEmpty()) {
            versions.invalidateLocal();
        }
        touched.addAll(escalated);
        for (UUID reviewId : touched) {
            closeIfComplete(reviewId, now);
        }
        return revoked;
    }

    // ---------------------------------------------------------------- 工具

    private LocalDate quarterStart(LocalDate date) {
        int month = ((date.getMonthValue() - 1) / 3) * 3 + 1;
        return LocalDate.of(date.getYear(), month, 1);
    }

    private UUID currentActorId() {
        return ActorContextHolder.current().map(actor -> actor.actorId())
                .orElseThrow(() -> new ApiException(401, "SESSION_REQUIRED", "复核需要已认证主体"));
    }

    private void audit(String action, UUID targetId, Map<String, Object> detail) {
        adminActions.insert(action, "authorization.access_review", targetId,
                objectMapper.writeValueAsString(detail));
    }
}
