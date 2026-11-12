package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.SessionPrincipal;
import com.acme.platform.identity.SessionService;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.idempotency.IdempotencyService;
import com.acme.platform.tenancy.TenantExecutionGate;
import com.acme.platform.tenancy.TenantLifecycleService;
import com.acme.platform.tenancy.TenantRecord;
import com.acme.platform.tenancy.TenantStatus;
import com.acme.platform.tenancy.TenantSupportAccessService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WP02-06：租户头与会话的绑定关系。
 *
 * <p>租户头只选择上下文，身份只能来自会话；头与令牌不一致、缺失、非法都必须拒绝，
 * 伪造主体头不得改变身份。
 */
@WebMvcTest(controllers = TenantController.class)
@Import(ProblemDetailsSupport.class)
class TenantHeaderMismatchTest {

    private static final String BEARER = "Bearer test-session-token";

    @MockitoBean
    private TenantLifecycleService lifecycleService;

    @MockitoBean
    private TenantSupportAccessService supportAccessService;

    @MockitoBean
    private TenantExecutionGate tenantExecutionGate;

    @MockitoBean
    private PlatformMetrics platformMetrics;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private IdentityProperties identityProperties;

    @Autowired
    private MockMvc mockMvc;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(identityProperties.getCookieName()).thenReturn("plt_session");
        when(identityProperties.getCsrfCookieName()).thenReturn("plt_csrf");
    }

    @Test
    void tenantHeaderMustMatchSessionTenant() throws Exception {
        when(sessionService.authenticateToken(eq(tenantB), anyString()))
                .thenThrow(new ApiException(401, "TENANT_MISMATCH", "会话不属于该租户"));

        mockMvc.perform(get("/api/v1/tenant")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", tenantB.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
    }

    @Test
    void missingTenantHeaderIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/tenant").header("Authorization", BEARER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verify(sessionService, never()).authenticateToken(any(), anyString());
    }

    @Test
    void malformedTenantHeaderIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/tenant")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", "not-a-uuid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verify(sessionService, never()).authenticateToken(any(), anyString());
    }

    @Test
    void forgedSubjectHeaderDoesNotChangeIdentity() throws Exception {
        stubSession(tenantA);
        when(lifecycleService.view(tenantA)).thenReturn(new TenantLifecycleService.TenantView(
                tenant(tenantA), null, List.of(), List.of(), List.of(), List.of()));

        mockMvc.perform(get("/api/v1/tenant")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", tenantA.toString())
                        .header("X-Subject-Id", UUID.randomUUID().toString())
                        .header("X-Actor-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.id").value(tenantA.toString()));
        verify(lifecycleService).view(tenantA);
    }

    @Test
    void platformOperatorHeaderIsRejectedInsideTenant() throws Exception {
        stubSession(tenantA);

        mockMvc.perform(get("/api/v1/tenant")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", tenantA.toString())
                        .header("X-Platform-Operator-Id", "ops-a"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_OPERATOR_NOT_ALLOWED"));
    }

    private void stubSession(UUID tenantId) {
        when(sessionService.authenticateToken(eq(tenantId), anyString())).thenReturn(
                new SessionPrincipal(tenantId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), "user", "admin@example.test", "管理员",
                        true, true, true, true));
    }

    private TenantRecord tenant(UUID id) {
        return new TenantRecord(id, "demo", "演示租户", TenantStatus.ACTIVE, "standard",
                OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, null);
    }
}
