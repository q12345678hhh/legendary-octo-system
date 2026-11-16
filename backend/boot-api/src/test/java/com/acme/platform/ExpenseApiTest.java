package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AccessScope;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.DecisionReason;
import com.acme.platform.expense.app.ExpenseBatchHold;
import com.acme.platform.expense.app.ExpenseDocument;
import com.acme.platform.expense.app.ExpenseService;
import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.MemberStatus;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.SessionPrincipal;
import com.acme.platform.identity.SessionService;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.idempotency.IdempotencyService;
import com.acme.platform.platform.security.PlatformSecurityService;
import com.acme.platform.tenancy.retention.RetentionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 单据预审 HTTP 边界：会话解析、权限码与职责分离都在控制器入口生效。 */
@WebMvcTest(controllers = ExpenseController.class)
@Import(ProblemDetailsSupport.class)
class ExpenseApiTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000e1");
    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000a1");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PERSON = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000a1");
    private static final UUID FILE = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000f1");
    private static final String BEARER = "Bearer test-session-token";

    @MockitoBean
    private ExpenseService expenses;

    @MockitoBean
    private AuthorizationGuard authorization;

    @MockitoBean
    private MemberStore memberStore;

    @MockitoBean
    private PlatformSecurityService security;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private IdentityProperties identityProperties;

    @MockitoBean
    private PlatformMetrics platformMetrics;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @MockitoBean
    private RetentionService retentionService;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(identityProperties.getCookieName()).thenReturn("plt_session");
        when(identityProperties.getCsrfCookieName()).thenReturn("plt_csrf");
        when(sessionService.authenticateToken(any(), anyString())).thenReturn(
                new SessionPrincipal(TENANT, UUID.randomUUID(), ACTOR, USER, MEMBER, "user",
                        "employee@example.test", "员工", true, true, true, true));
        when(authorization.actor()).thenReturn(new ActorContext(TENANT, ACTOR, USER, MEMBER,
                "user", true, null));
        when(memberStore.findById(MEMBER)).thenReturn(Optional.of(new TenantMember(MEMBER, TENANT,
                USER, PERSON, MemberStatus.ACTIVE, false, false, false)));
        when(authorization.decide(any(), any(), any(), any())).thenReturn(allowed());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        when(sessionService.authenticateToken(any(), anyString())).thenReturn(null);

        mockMvc.perform(get("/api/v1/expense/documents")
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deniedReadNeverReachesService() throws Exception {
        when(authorization.require(anyString(), anyString(), any(), any())).thenThrow(
                ApiException.forbidden("EXPENSE_DOCUMENT_FORBIDDEN", "只能查看本人的单据"));

        mockMvc.perform(get("/api/v1/expense/documents")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXPENSE_DOCUMENT_FORBIDDEN"));

        verify(expenses, never()).list(any(), any(Boolean.class), any(), any(Integer.class));
        verify(security).record(eq(TENANT), eq("incident"), eq("medium"),
                contains("expense.document.read"), any(), isNull());
    }

    @Test
    void listUsesSessionPersonAndHidesReviewScopeWithoutPermission() throws Exception {
        when(authorization.decide(any(), any(), any(), any())).thenReturn(denied());
        when(expenses.list(PERSON, false, null, 50)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/expense/documents")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void submitReturnsPreReviewConclusion() throws Exception {
        when(expenses.submit(any(), anyString(), any())).thenReturn(view("posted"));

        mockMvc.perform(post("/api/v1/expense/documents")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileId":"%s","docType":"invoice"}
                                """.formatted(FILE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.status").value("posted"))
                .andExpect(jsonPath("$.document.amount").value(128.50));
    }

    @Test
    void selfReviewIsRejectedAsSeparationOfDutyViolation() throws Exception {
        when(expenses.review(any(), any(), any(), any(), any())).thenThrow(
                ApiException.forbidden("EXPENSE_SOD_VIOLATION", "不能复核自己提交的单据"));

        mockMvc.perform(post("/api/v1/expense/documents/{id}/review", UUID.randomUUID())
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"approve","comment":"通过"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXPENSE_SOD_VIOLATION"));

        verify(security).record(eq(TENANT), eq("incident"), eq("medium"),
                contains("expense.document.review"), any(), isNull());
    }

    @Test
    void holdsListReturnsActiveBatchAnomalies() throws Exception {
        when(expenses.holds("active", 100)).thenReturn(List.of(hold()));

        mockMvc.perform(get("/api/v1/expense/holds")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scopeType").value("vendor"))
                .andExpect(jsonPath("$[0].status").value("released"));
    }

    @Test
    void releaseHoldDelegatesToService() throws Exception {
        UUID holdId = UUID.randomUUID();
        when(expenses.releaseHold(PERSON, holdId, "已核对为正常批量报销")).thenReturn(hold());

        mockMvc.perform(post("/api/v1/expense/holds/{id}/release", holdId)
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"已核对为正常批量报销"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("released"));
    }

    @Test
    void voidRequiresReasonFromService() throws Exception {
        when(expenses.voidDocument(any(), any(), any())).thenThrow(
                ApiException.badRequest("EXPENSE_VOID_REASON_REQUIRED", "撤销必须填写原因"));

        mockMvc.perform(post("/api/v1/expense/documents/{id}/void", UUID.randomUUID())
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPENSE_VOID_REASON_REQUIRED"));
    }

    private static AccessDecision allowed() {
        return new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:employee"),
                AccessScope.self(PERSON), Map.of(), 1L, OffsetDateTime.now(), false);
    }

    private static AccessDecision denied() {
        return new AccessDecision(false, DecisionReason.NO_ALLOW, List.of("DENY:no-grant"),
                AccessScope.self(PERSON), Map.of(), 1L, OffsetDateTime.now(), false);
    }

    private static ExpenseBatchHold hold() {
        return new ExpenseBatchHold(UUID.randomUUID(), "vendor", "云图科技",
                "24 小时内同一供应商提交 5 张单据", 24, 5, new BigDecimal("640.00"),
                "released", PERSON, OffsetDateTime.now(), PERSON, OffsetDateTime.now(),
                "已核对为正常批量报销");
    }

    private static ExpenseService.DocumentView view(String status) {
        ExpenseDocument document = new ExpenseDocument(UUID.randomUUID(), PERSON, FILE, "invoice",
                status, "CNY", new BigDecimal("128.50"), "044", "88000011", "云图科技",
                "91310000XYZ", LocalDate.of(2026, 11, 10), "差旅", List.of(), Map.of(),
                "expense-rules-2026.11", 3, null, null, null, null, null, null, null,
                OffsetDateTime.now());
        return new ExpenseService.DocumentView(document, null);
    }
}
