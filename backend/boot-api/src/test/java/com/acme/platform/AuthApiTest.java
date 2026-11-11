package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.identity.AccountService;
import com.acme.platform.identity.AuthenticationService;
import com.acme.platform.identity.IdentityGuard;
import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.InvitationService;
import com.acme.platform.identity.MfaService;
import com.acme.platform.identity.SessionPrincipal;
import com.acme.platform.identity.SessionService;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.idempotency.IdempotencyService;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 认证入口的 HTTP 契约：Cookie 属性、MFA 挑战不下发会话、CSRF 双提交与占位头失效。
 */
@WebMvcTest(controllers = AuthController.class)
@Import(ProblemDetailsSupport.class)
class AuthApiTest {

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private MfaService mfaService;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private InvitationService invitationService;

    @MockitoBean
    private IdentityGuard guard;

    @MockitoBean
    private IdentityProperties identityProperties;

    @MockitoBean
    private PlatformMetrics platformMetrics;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @Autowired
    private MockMvc mockMvc;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(identityProperties.getCookieName()).thenReturn("plt_session");
        when(identityProperties.getCsrfCookieName()).thenReturn("plt_csrf");
        when(identityProperties.getSessionAbsoluteTtl()).thenReturn(Duration.ofHours(8));
        when(identityProperties.isCookieSecure()).thenReturn(true);
        when(guard.requireActor()).thenReturn(new ActorContext(tenantId, actorId, userId, memberId,
                "user", true, sessionId));
        when(guard.requireUser()).thenReturn(new ActorContext(tenantId, actorId, userId, memberId,
                "user", true, sessionId));
    }

    @Test
    void loginIssuesHttpOnlySessionAndCsrfCookies() throws Exception {
        SessionPrincipal principal = principal();
        when(authenticationService.login(eq(tenantId), any(), any())).thenReturn(
                new AuthenticationService.LoginResult("session", principal, "plt_s_raw",
                        OffsetDateTime.now().plusHours(8), null, null));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.test\",\"password\":\"Str0ng-Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("session"))
                .andExpect(jsonPath("$.principal.userId").value(userId.toString()))
                .andReturn();

        String setCookies = String.join(" | ", result.getResponse().getHeaders("Set-Cookie"));
        org.assertj.core.api.Assertions.assertThat(setCookies)
                .contains("plt_session=plt_s_raw")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Secure")
                .contains("plt_csrf=");
        org.assertj.core.api.Assertions.assertThat(setCookies).doesNotContain("plt_csrf=;");
    }

    @Test
    void mfaChallengeDoesNotIssueSessionCookie() throws Exception {
        when(authenticationService.login(eq(tenantId), any(), any())).thenReturn(
                new AuthenticationService.LoginResult("mfa_required", null, null, null,
                        "plt_mfa_raw", OffsetDateTime.now().plusMinutes(5)));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.test\",\"password\":\"Str0ng-Passw0rd!\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("mfa_required"))
                .andExpect(jsonPath("$.mfaToken").value("plt_mfa_raw"))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .isEmpty();
    }

    @Test
    void protectedEndpointRequiresSession() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
    }

    @Test
    void subjectHeaderCannotAuthenticate() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("X-Subject-Id", "forged-actor"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
    }

    @Test
    void cookieWriteRequiresMatchingCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-Tenant-Id", tenantId.toString())
                        .cookie(new Cookie("plt_session", "plt_s_raw"))
                        .cookie(new Cookie("plt_csrf", "csrf-value")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REQUIRED"));
    }

    @Test
    void cookieWriteWithMatchingCsrfSucceeds() throws Exception {
        when(sessionService.authenticate(eq(tenantId), eq("plt_s_raw"))).thenReturn(principal());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-Tenant-Id", tenantId.toString())
                        .header(SessionCookies.CSRF_HEADER, "csrf-value")
                        .cookie(new Cookie("plt_session", "plt_s_raw"))
                        .cookie(new Cookie("plt_csrf", "csrf-value")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("plt_session=;")));
    }

    @Test
    void meReturnsAuthenticatedPrincipal() throws Exception {
        when(sessionService.authenticate(eq(tenantId), eq("plt_s_raw"))).thenReturn(principal());
        when(authenticationService.me(any())).thenReturn(new AuthenticationService.MeView(tenantId,
                actorId, userId, memberId, "user", "admin@example.test", "管理员",
                true, true, true, true, true));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("X-Tenant-Id", tenantId.toString())
                        .cookie(new Cookie("plt_session", "plt_s_raw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@example.test"))
                .andExpect(jsonPath("$.mfaSatisfied").value(true));
    }

    private SessionPrincipal principal() {
        return new SessionPrincipal(tenantId, actorId, userId, memberId, sessionId, "user",
                "admin@example.test", "管理员", true, true, true, true);
    }
}
