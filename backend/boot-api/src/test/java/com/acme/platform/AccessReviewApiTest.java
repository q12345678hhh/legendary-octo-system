package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AccessRequestService;
import com.acme.platform.authorization.AccessRequestStore;
import com.acme.platform.authorization.AccessReviewService;
import com.acme.platform.authorization.AccessReviewStore;
import com.acme.platform.authorization.AccessScope;
import com.acme.platform.authorization.AuthorizationAdminService;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.DecisionReason;
import com.acme.platform.authorization.ScopeType;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 权限申请与季度复核 API：写操作受 MFA 与职责分离保护。 */
@WebMvcTest(controllers = AuthorizationAdminController.class)
@Import(ProblemDetailsSupport.class)
class AccessReviewApiTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String BEARER = "Bearer test-session-token";

    @MockitoBean
    private AuthorizationGuard guard;

    @MockitoBean
    private AuthorizationAdminService adminService;

    @MockitoBean
    private AccessRequestService accessRequestService;

    @MockitoBean
    private AccessReviewService accessReviewService;

    @MockitoBean
    private com.acme.platform.identity.SessionService sessionService;

    @MockitoBean
    private com.acme.platform.identity.IdentityProperties identityProperties;

    @MockitoBean
    private com.acme.platform.platform.PlatformMetrics platformMetrics;

    @MockitoBean
    private com.acme.platform.platform.idempotency.IdempotencyService idempotencyService;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(identityProperties.getCookieName()).thenReturn("plt_session");
        when(identityProperties.getCsrfCookieName()).thenReturn("plt_csrf");
        when(sessionService.authenticateToken(any(), anyString())).thenReturn(
                new com.acme.platform.identity.SessionPrincipal(TENANT, UUID.randomUUID(),
                        ACTOR, null, null, "user", "admin@example.test", "管理员",
                        true, true, true, true));
        when(guard.actor()).thenReturn(new ActorContext(TENANT, ACTOR, UUID.randomUUID(),
                UUID.randomUUID(), "user", true, null));
        when(guard.require(anyString(), anyString(), any(), any())).thenReturn(allowed());
    }

    @Test
    void createAccessRequestReturnsPendingRequest() throws Exception {
        UUID id = UUID.randomUUID();
        when(accessRequestService.create(any())).thenReturn(request(id, "pending"));

        mockMvc.perform(post("/api/v1/authorization/access-requests")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"payroll-approver\",\"scopeType\":\"tenant\","
                                + "\"reason\":\"薪酬批准需要\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.state").value("pending"));
        verify(guard).require(eq("authorization.access.request"), anyString(), any(), any());
    }

    @Test
    void approveRequiresMfa() throws Exception {
        org.mockito.Mockito.doThrow(
                        ApiException.forbidden("MFA_REQUIRED", "该操作要求先完成 MFA 验证"))
                .when(guard).requireMfa();

        mockMvc.perform(post("/api/v1/authorization/access-requests/"
                        + UUID.randomUUID() + "/approve")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"同意\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        verify(accessRequestService, never()).approve(any(), any());
    }

    @Test
    void selfApprovalReturnsSodReason() throws Exception {
        when(accessRequestService.approve(any(), any()))
                .thenThrow(ApiException.forbidden("SOD_SELF_APPROVAL", "不能批准自己的权限申请"));

        mockMvc.perform(post("/api/v1/authorization/access-requests/"
                        + UUID.randomUUID() + "/approve")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SOD_SELF_APPROVAL"));
    }

    @Test
    void listReviewsReturnsReviewWithoutItems() throws Exception {
        when(accessReviewService.list(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(review()));

        mockMvc.perform(get("/api/v1/authorization/access-reviews")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("open"))
                .andExpect(jsonPath("$[0].periodStart").value("2026-10-01"));
    }

    @Test
    void revokeRequiresMfa() throws Exception {
        org.mockito.Mockito.doThrow(
                        ApiException.forbidden("MFA_REQUIRED", "该操作要求先完成 MFA 验证"))
                .when(guard).requireMfa();

        mockMvc.perform(post("/api/v1/authorization/access-reviews/" + UUID.randomUUID()
                        + "/items/" + UUID.randomUUID() + "/revoke")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        verify(accessReviewService, never()).revoke(any(), any(), any());
    }

    private AccessRequestStore.AccessRequestRow request(UUID id, String state) {
        return new AccessRequestStore.AccessRequestRow(id, ACTOR, ACTOR, "payroll-approver",
                ScopeType.TENANT, null, OffsetDateTime.now().plusDays(10), "薪酬批准需要",
                state, null, null, null, null, OffsetDateTime.now());
    }

    private AccessReviewStore.ReviewRow review() {
        return new AccessReviewStore.ReviewRow(UUID.randomUUID(), LocalDate.of(2026, 10, 1),
                LocalDate.of(2027, 1, 1), "open", OffsetDateTime.now().plusDays(14),
                OffsetDateTime.now(), null);
    }

    private AccessDecision allowed() {
        return new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:employee:SELF"),
                AccessScope.tenant(), Map.of(), 3L, OffsetDateTime.now(), false);
    }
}
