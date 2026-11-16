package com.acme.platform;

import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.expense.app.ExpenseAuditEvent;
import com.acme.platform.expense.app.ExpenseBatchHold;
import com.acme.platform.expense.app.ExpensePolicy;
import com.acme.platform.expense.app.ExpenseService;
import com.acme.platform.expense.ocr.ExtractedFields;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.security.PlatformSecurityService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单据上传、预审、人工复核、入账与撤销。
 *
 * <p>员工标识永远从会话解析，请求体不能指定他人；复核与入账使用独立权限码，
 * 提交人复核自己单据会被服务层拒绝（职责分离）。
 */
@RestController
@RequestMapping("/api/v1/expense")
class ExpenseController {

    private static final String RESOURCE_DOCUMENT = "expense.document";
    private static final String RESOURCE_POLICY = "expense.policy";

    private final ExpenseService expenses;
    private final AuthorizationGuard authorization;
    private final MemberStore memberStore;
    private final PlatformSecurityService security;

    ExpenseController(ExpenseService expenses, AuthorizationGuard authorization,
                      MemberStore memberStore, PlatformSecurityService security) {
        this.expenses = expenses;
        this.authorization = authorization;
        this.memberStore = memberStore;
        this.security = security;
    }

    record LineItemRequest(String description, BigDecimal amount) {
    }

    record ManualFieldsRequest(BigDecimal amount,
                               String currency,
                               String invoiceCode,
                               String invoiceNo,
                               String vendorName,
                               String vendorTaxNo,
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate occurredOn,
                               String category,
                               String title,
                               List<LineItemRequest> lines) {
    }

    record CreateRequest(UUID fileId, String docType, ManualFieldsRequest fields) {
    }

    record ReviewRequest(String decision, String comment,
                         List<ExpenseService.Override> overrides) {
    }

    record VoidRequest(String reason) {
    }

    record ReleaseHoldRequest(String note) {
    }

    record PolicyRequest(boolean autoPostEnabled,
                         BigDecimal autoPostLimit,
                         int windowDays,
                         String legalTitle,
                         String taxId,
                         List<String> vendorAllowlist,
                         List<String> manualCategories,
                         List<String> allowedCategories,
                         BigDecimal minConfidence,
                         boolean verifyRequired) {
    }

    @PostMapping("/documents")
    ExpenseService.DocumentView submit(@RequestBody CreateRequest request) {
        Actor actor = requireActor("expense.document.submit", null, Map.of());
        ExtractedFields fields = request.fields() == null ? null : toFields(request.fields());
        return expenses.submit(actor.personId(), actor.subjectId(),
                new ExpenseService.CreateInput(request.fileId(), request.docType(), fields));
    }

    @GetMapping("/documents")
    List<ExpenseService.DocumentView> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        Actor actor = requireActor("expense.document.read", null, Map.of());
        boolean canReview = can("expense.document.review");
        return expenses.list(actor.personId(), canReview, status, limit);
    }

    @GetMapping("/documents/{documentId}")
    ExpenseService.DocumentView detail(@PathVariable UUID documentId) {
        Actor actor = requireActor("expense.document.read", documentId, Map.of());
        return expenses.detail(actor.personId(), can("expense.document.review"), documentId);
    }

    @GetMapping("/documents/{documentId}/audit")
    List<ExpenseAuditEvent> audit(@PathVariable UUID documentId) {
        Actor actor = requireActor("expense.document.read", documentId, Map.of());
        return expenses.audit(actor.personId(), can("expense.document.review"), documentId);
    }

    @PostMapping("/documents/{documentId}/review")
    ExpenseService.DocumentView review(@PathVariable UUID documentId,
                                       @RequestBody ReviewRequest request) {
        Actor actor = requireActor("expense.document.review", documentId, Map.of());
        try {
            return expenses.review(actor.personId(), documentId, request.decision(),
                    request.comment(), request.overrides());
        } catch (ApiException e) {
            denyIfForbidden(e, actor, "expense.document.review", documentId);
            throw e;
        }
    }

    @PostMapping("/documents/{documentId}/post")
    ExpenseService.DocumentView post(@PathVariable UUID documentId) {
        Actor actor = requireActor("expense.document.post", documentId, Map.of());
        try {
            return expenses.post(actor.personId(), documentId);
        } catch (ApiException e) {
            denyIfForbidden(e, actor, "expense.document.post", documentId);
            throw e;
        }
    }

    @PostMapping("/documents/{documentId}/void")
    ExpenseService.DocumentView voidDocument(@PathVariable UUID documentId,
                                             @RequestBody VoidRequest request) {
        Actor actor = requireActor("expense.document.void", documentId, Map.of());
        try {
            return expenses.voidDocument(actor.personId(), documentId, request.reason());
        } catch (ApiException e) {
            denyIfForbidden(e, actor, "expense.document.void", documentId);
            throw e;
        }
    }

    @GetMapping("/holds")
    List<ExpenseBatchHold> holds(
            @RequestParam(required = false, defaultValue = "active") String status) {
        requireActor("expense.document.review", null, Map.of());
        return expenses.holds(status, 100);
    }

    @PostMapping("/holds/{holdId}/release")
    ExpenseBatchHold releaseHold(@PathVariable UUID holdId,
                                 @RequestBody ReleaseHoldRequest request) {
        Actor actor = requireActor("expense.document.post", null, Map.of());
        try {
            return expenses.releaseHold(actor.personId(), holdId, request.note());
        } catch (ApiException e) {
            denyIfForbidden(e, actor, "expense.document.post", holdId);
            throw e;
        }
    }

    @GetMapping("/policy")
    ExpensePolicy policy() {
        requireActor("expense.document.review", null, Map.of());
        return expenses.policy();
    }

    @PutMapping("/policy")
    ExpensePolicy updatePolicy(@RequestBody PolicyRequest request) {
        Actor actor = requireActor("expense.policy.manage", null, Map.of());
        return expenses.updatePolicy(actor.personId(), new ExpenseService.PolicyInput(
                request.autoPostEnabled(), request.autoPostLimit(), request.windowDays(),
                request.legalTitle(), request.taxId(), request.vendorAllowlist(),
                request.manualCategories(), request.allowedCategories(),
                request.minConfidence(), request.verifyRequired()));
    }

    private Actor requireActor(String permission, UUID resourceId,
                               Map<String, Object> attributes) {
        ActorContext actor = authorization.actor();
        UUID personId = memberStore.findById(actor.memberId())
                .map(TenantMember::personId)
                .filter(java.util.Objects::nonNull)
                .orElseThrow(() -> ApiException.forbidden("PERSON_NOT_BOUND",
                        "当前账号未绑定员工"));
        Map<String, Object> merged = new java.util.LinkedHashMap<>(attributes);
        merged.put("personId", personId);
        merged.put("ownerId", personId);
        try {
            authorization.require(permission, permission.startsWith("expense.policy")
                    ? RESOURCE_POLICY : RESOURCE_DOCUMENT, resourceId, merged);
        } catch (ApiException e) {
            if (e.status() == 403) {
                recordDenied(actor, permission, resourceId, personId, e.getMessage());
            }
            throw e;
        }
        return new Actor(personId, actor.actorId() == null ? null : actor.actorId().toString());
    }

    private void denyIfForbidden(ApiException e, Actor actor, String permission, UUID resourceId) {
        if (e.status() == 403) {
            recordDenied(authorization.actor(), permission, resourceId, actor.personId(),
                    e.getMessage());
        }
    }

    /** 越权告警：记录失败不能覆盖原始 403。 */
    private void recordDenied(ActorContext actor, String permission, UUID resourceId,
                              UUID personId, String detail) {
        if (actor == null || actor.tenantId() == null) {
            return;
        }
        try {
            security.record(actor.tenantId(), "incident", "medium", "越权访问被拒绝：" + permission,
                    Map.of("permission", permission,
                            "resourceId", resourceId == null ? "" : resourceId.toString(),
                            "personId", personId == null ? "" : personId.toString(),
                            "reason", detail == null ? "" : detail),
                    null);
        } catch (RuntimeException ignored) {
            // 安全事件记录失败不影响原始 403 响应
        }
    }

    private boolean can(String permission) {
        ActorContext actor = authorization.actor();
        Optional<TenantMember> member = memberStore.findById(actor.memberId());
        if (member.isEmpty() || member.get().personId() == null) {
            return false;
        }
        UUID personId = member.get().personId();
        try {
            return authorization.decide(permission,
                    permission.startsWith("expense.policy") ? RESOURCE_POLICY : RESOURCE_DOCUMENT,
                    null, Map.of("personId", personId, "ownerId", personId)).allowed();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ExtractedFields toFields(ManualFieldsRequest request) {
        List<ExtractedFields.LineItem> lines = request.lines() == null ? List.of()
                : request.lines().stream()
                .map(line -> new ExtractedFields.LineItem(line.description(), line.amount()))
                .toList();
        return new ExtractedFields(request.amount(), request.currency(), request.invoiceCode(),
                request.invoiceNo(), request.vendorName(), request.vendorTaxNo(),
                request.occurredOn(), request.category(), request.title(), lines);
    }

    private record Actor(UUID personId, String subjectId) {
    }
}
