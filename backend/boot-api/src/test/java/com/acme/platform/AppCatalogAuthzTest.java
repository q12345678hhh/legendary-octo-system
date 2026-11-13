package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.appcatalog.AppCatalogService;
import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AccessScope;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.DecisionReason;
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

/** 模块目录 HTTP 鉴权：受理/启用必须由租户级复核人执行，非法流转返回 409。 */
@WebMvcTest(controllers = AppCatalogController.class)
@Import(ProblemDetailsSupport.class)
class AppCatalogAuthzTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();
    private static final String BEARER = "Bearer test-session-token";

    @MockitoBean
    private AppCatalogService service;

    @MockitoBean
    private AuthorizationGuard authorization;

    @MockitoBean
    private MemberStore memberStore;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private IdentityProperties identityProperties;

    @MockitoBean
    private PlatformMetrics platformMetrics;

    @MockitoBean
    private IdempotencyService idempotencyService;

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
        when(authorization.decide(any(), any(), any(), any())).thenReturn(employeeScope());
    }

    @Test
    void allSuggestionsRequireTenantWideReviewer() throws Exception {
        mockMvc.perform(get("/api/v1/app-catalog/suggestions")
                        .param("scope", "all")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APP_SUGGESTION_REVIEWER_UNAVAILABLE"));
    }

    @Test
    void mySuggestionsUseSessionPersonAndDoNotExposeOthers() throws Exception {
        when(service.listSuggestions(PERSON, false)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/app-catalog/suggestions")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void illegalTransitionIsReportedAsConflict() throws Exception {
        UUID suggestionId = UUID.randomUUID();
        when(authorization.decide(any(), any(), any(), any())).thenReturn(reviewerScope());
        when(service.act(any(), any(), any())).thenThrow(ApiException.conflict(
                "APP_SUGGESTION_TRANSITION_INVALID", "建议当前状态 enabled 不允许执行动作 accept"));

        mockMvc.perform(post("/api/v1/app-catalog/suggestions/{id}/actions", suggestionId)
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"accept","configOwnerPersonId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APP_SUGGESTION_TRANSITION_INVALID"));
    }

    private AccessDecision employeeScope() {
        return new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:employee"),
                AccessScope.self(PERSON), Map.of(), 1L, OffsetDateTime.now(), false);
    }

    private AccessDecision reviewerScope() {
        return new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:business-owner"),
                AccessScope.tenant(), Map.of(), 1L, OffsetDateTime.now(), false);
    }
}
