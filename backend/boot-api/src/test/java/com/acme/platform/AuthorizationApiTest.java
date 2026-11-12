package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AccessScope;
import com.acme.platform.authorization.AuthorizationAdminService;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.AuthorizationService;
import com.acme.platform.authorization.DecisionReason;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
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

/** 权限解释器与模拟器：解释需安全权限，模拟需 MFA，自身判定不需要额外权限。 */
@WebMvcTest(controllers = AuthorizationController.class)
@Import(ProblemDetailsSupport.class)
class AuthorizationApiTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String BEARER = "Bearer test-session-token";

    @MockitoBean
    private AuthorizationGuard guard;

    @MockitoBean
    private AuthorizationService service;

    @MockitoBean
    private AuthorizationAdminService adminService;

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
        when(guard.decide(anyString(), anyString(), any(), any())).thenReturn(allowed());
        when(guard.explainFor(any(), anyString(), anyString(), any(), any())).thenReturn(allowed());
        when(service.currentVersion()).thenReturn(3L);
    }

    @Test
    void permissionsRequireDecisionCheckPermission() throws Exception {
        when(guard.require(eq("authorization.decision.check"), anyString(), any(), any()))
                .thenReturn(allowed());

        mockMvc.perform(get("/api/v1/authorization/permissions")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='directory.person.read')]").exists());
    }

    @Test
    void explainRequiresSecurityPermissionAndReturnsFactors() throws Exception {
        when(guard.require(eq("authorization.decision.explain"), anyString(), any(), any()))
                .thenReturn(allowed());
        when(guard.explainFor(eq(ACTOR), eq("directory.person.read"), eq("directory.person"),
                any(), any())).thenReturn(allowed());

        mockMvc.perform(post("/api/v1/authorization/decisions/explain")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permission\":\"directory.person.read\","
                                + "\"resourceType\":\"directory.person\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.factors[0]").value("GRANT:employee:SELF"));
    }

    @Test
    void simulationRequiresMfa() throws Exception {
        when(guard.require(eq("authorization.decision.explain"), anyString(), any(), any()))
                .thenReturn(allowed());
        org.mockito.Mockito.doThrow(ApiException.forbidden("MFA_REQUIRED", "该操作要求先完成 MFA 验证"))
                .when(guard).requireMfa();

        mockMvc.perform(post("/api/v1/authorization/simulations")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grants\":[],\"checks\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        verify(guard).requireMfa();
    }

    private AccessDecision allowed() {
        return new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:employee:SELF"),
                AccessScope.tenant(), Map.of(), 3L, OffsetDateTime.now(), false);
    }
}
