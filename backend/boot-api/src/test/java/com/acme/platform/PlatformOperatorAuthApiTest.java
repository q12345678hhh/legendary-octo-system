package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.SessionService;
import com.acme.platform.identity.operator.PlatformActorContext;
import com.acme.platform.identity.operator.PlatformOpsProperties;
import com.acme.platform.identity.operator.PlatformOperatorService;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.idempotency.IdempotencyService;
import com.acme.platform.tenancy.TenantExecutionGate;
import com.acme.platform.tenancy.TenantSupportAccessService;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
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

/** 平台运营独立认证：Cookie 隔离、CSRF、MFA 会话标记与登出清理。 */
@WebMvcTest(controllers = PlatformAuthController.class)
@Import(ProblemDetailsSupport.class)
class PlatformOperatorAuthApiTest {

    @MockitoBean
    private PlatformOperatorService operatorService;

    @MockitoBean
    private PlatformOpsProperties opsProperties;

    @MockitoBean
    private IdentityProperties identityProperties;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @MockitoBean
    private PlatformMetrics platformMetrics;

    @MockitoBean
    private TenantExecutionGate tenantExecutionGate;

    @MockitoBean
    private TenantSupportAccessService tenantSupportAccessService;

    @Autowired
    private MockMvc mockMvc;

    private static final String OPS_COOKIE = "plt_ops_session";
    private static final String OPS_CSRF = "plt_ops_csrf";

    private final PlatformActorContext owner = new PlatformActorContext(UUID.randomUUID(),
            UUID.randomUUID(), "owner@example.test", "运营负责人", "platform-owner", true);

    @BeforeEach
    void stubCookies() {
        when(opsProperties.getCookieName()).thenReturn(OPS_COOKIE);
        when(opsProperties.getCsrfCookieName()).thenReturn(OPS_CSRF);
        when(opsProperties.getCookiePath()).thenReturn("/platform");
        when(opsProperties.getSessionAbsoluteTtl()).thenReturn(Duration.ofHours(8));
        when(opsProperties.isCookieSecure()).thenReturn(true);
        when(identityProperties.getCookieName()).thenReturn("plt_session");
        when(identityProperties.getCsrfCookieName()).thenReturn("plt_csrf");
        when(operatorService.authenticate("ops-token")).thenReturn(owner);
        when(operatorService.me(any())).thenReturn(new PlatformOperatorService.MeView(
                owner.operatorId(), owner.email(), owner.displayName(), owner.roleCode(), true,
                List.copyOf(owner.permissions())));
    }

    @Test
    void loginSetsSeparatedSessionAndCsrfCookies() throws Exception {
        when(operatorService.login(eq("owner@example.test"), eq("password"), eq("123456"),
                any(), any())).thenReturn(new PlatformOperatorService.LoginResult(
                "ops-token", owner, OffsetDateTime.now().plusHours(8)));

        MvcResult result = mockMvc.perform(post("/platform/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"owner@example.test","password":"password","totpCode":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operator.roleCode").value("platform-owner"))
                .andReturn();

        List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
        String session = cookies.stream().filter(value -> value.startsWith(OPS_COOKIE))
                .findFirst().orElseThrow();
        String csrf = cookies.stream().filter(value -> value.startsWith(OPS_CSRF))
                .findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(session)
                .contains("Path=/platform")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Secure");
        org.assertj.core.api.Assertions.assertThat(csrf)
                .contains("Path=/platform")
                .doesNotContain("HttpOnly");
    }

    @Test
    void meReturnsPermissionsForNavigation() throws Exception {
        mockMvc.perform(get("/platform/api/v1/auth/me").cookie(new Cookie(OPS_COOKIE, "ops-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorId").value(owner.operatorId().toString()))
                .andExpect(jsonPath("$.permissions.length()").value(12))
                .andExpect(jsonPath("$.permissions[0]").exists());
    }

    @Test
    void logoutClearsBothCookies() throws Exception {
        MvcResult result = mockMvc.perform(post("/platform/api/v1/auth/logout")
                        .cookie(new Cookie(OPS_COOKIE, "ops-token"),
                                new Cookie(OPS_CSRF, "csrf-token"))
                        .header("X-CSRF-Token", "csrf-token"))
                .andExpect(status().isNoContent())
                .andReturn();

        List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
        org.assertj.core.api.Assertions.assertThat(cookies).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(cookies)
                .allSatisfy(value -> org.assertj.core.api.Assertions.assertThat(value)
                        .contains("Max-Age=0").contains("Path=/platform"));
    }

    @Test
    void loginFailureReturnsStableCode() throws Exception {
        when(operatorService.login(any(), any(), any(), any(), any()))
                .thenThrow(new ApiException(401, "PLATFORM_LOGIN_FAILED", "邮箱或密码不正确"));

        mockMvc.perform(post("/platform/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.test\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLATFORM_LOGIN_FAILED"));
    }

    @Test
    void mfaEndpointsRequireOperatorSessionAndCsrf() throws Exception {
        mockMvc.perform(post("/platform/api/v1/auth/mfa/start"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLATFORM_SESSION_REQUIRED"));

        mockMvc.perform(post("/platform/api/v1/auth/mfa/start")
                        .cookie(new Cookie(OPS_COOKIE, "ops-token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_CSRF_REQUIRED"));
    }
}
