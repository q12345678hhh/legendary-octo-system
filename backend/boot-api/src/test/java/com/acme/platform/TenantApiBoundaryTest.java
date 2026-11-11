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

import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.SessionPrincipal;
import com.acme.platform.identity.SessionService;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.idempotency.IdempotencyService;
import com.acme.platform.tenancy.SupportAccessStatus;
import com.acme.platform.tenancy.TenantExecutionGate;
import com.acme.platform.tenancy.TenantLifecycleService;
import com.acme.platform.tenancy.TenantRecord;
import com.acme.platform.tenancy.TenantStatus;
import com.acme.platform.tenancy.TenantSupportAccess;
import com.acme.platform.tenancy.TenantSupportAccessService;
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

/**
 * 平台域与租户域的边界、冻结写阻断与支持访问只读/醒目标识。
 */
@WebMvcTest(controllers = {TenantController.class, PlatformTenantController.class})
@Import(ProblemDetailsSupport.class)
class TenantApiBoundaryTest {

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

    private final UUID tenantId = UUID.randomUUID();
    private static final String BEARER = "Bearer test-session-token";

    @BeforeEach
    void authenticateRequests() {
        when(identityProperties.getCookieName()).thenReturn("plt_session");
        when(identityProperties.getCsrfCookieName()).thenReturn("plt_csrf");
        when(sessionService.authenticateToken(any(), anyString())).thenReturn(
                new SessionPrincipal(tenantId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), "user", "admin@example.test", "管理员",
                        true, true, true, true));
    }

    @Test
    void platformRequestRequiresOperatorHeader() throws Exception {
        mockMvc.perform(get("/platform/api/v1/tenants/{id}", tenantId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLATFORM_OPERATOR_REQUIRED"));
    }

    @Test
    void platformRequestMustNotCarryTenantContext() throws Exception {
        mockMvc.perform(get("/platform/api/v1/tenants/{id}", tenantId)
                        .header("X-Platform-Operator-Id", "ops-a")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_TENANT_CONTEXT_FORBIDDEN"));
    }

    @Test
    void tenantRequestMustNotCarryPlatformOperator() throws Exception {
        mockMvc.perform(get("/api/v1/tenant")
                        .header("Authorization", BEARER)
                        .header("X-Platform-Operator-Id", "ops-a")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_OPERATOR_NOT_ALLOWED"));
    }

    @Test
    void tenantViewIsScopedByTenantContext() throws Exception {
        TenantRecord tenant = tenant(TenantStatus.ACTIVE);
        when(lifecycleService.view(tenantId)).thenReturn(new TenantLifecycleService.TenantView(
                tenant, null, List.of(), List.of(), List.of(), List.of()));

        mockMvc.perform(get("/api/v1/tenant")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.id").value(tenantId.toString()))
                .andExpect(jsonPath("$.tenant.status").value("active"));
    }

    @Test
    void frozenTenantWriteIsBlockedButAppealIsAllowed() throws Exception {
        when(tenantExecutionGate.assertBusinessWrite(any()))
                .thenThrow(new ApiException(423, "TENANT_FROZEN", "租户已冻结"));

        mockMvc.perform(post("/api/v1/tenant/profile")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brandName":"演示","brandColor":"#1f6feb","locale":"zh-CN",
                                 "timezone":"Asia/Shanghai","features":{}}
                                """))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.code").value("TENANT_FROZEN"));

        mockMvc.perform(post("/api/v1/tenant/appeals")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"冻结期间需要导出个人工时记录\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void subjectHeaderIsIgnoredAndSessionIsRequired() throws Exception {
        mockMvc.perform(post("/api/v1/tenant/closing")
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("X-Subject-Id", "employee-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"业务调整，申请注销\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
    }

    @Test
    void supportAccessIsReadOnlyAndReturnsBannerHeaders() throws Exception {
        UUID accessId = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(5);
        TenantSupportAccess access = new TenantSupportAccess(accessId, tenantId, "TCK-1001", "ops-a",
                "核对导出结果与审计链", 5, "tenant_admin_read", SupportAccessStatus.APPROVED,
                OffsetDateTime.now(), "ops-b", OffsetDateTime.now(), expiresAt, null, null);
        when(supportAccessService.enter(tenantId, accessId)).thenReturn(
                new TenantSupportAccessService.SupportGrant(accessId, tenantId, "TCK-1001", "ops-a",
                        true, expiresAt, access.banner(OffsetDateTime.now())));

        mockMvc.perform(get("/api/v1/tenant")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", tenantId.toString())
                        .header(SupportAccessBannerFilter.ACCESS_HEADER, accessId.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Support-Access-Ticket", "TCK-1001"))
                .andExpect(header().string("X-Support-Access-Notice",
                        org.hamcrest.Matchers.containsString("read-only")));

        mockMvc.perform(post("/api/v1/tenant/appeals")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", tenantId.toString())
                        .header(SupportAccessBannerFilter.ACCESS_HEADER, accessId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"支持访问不允许写操作\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUPPORT_ACCESS_READ_ONLY"));
    }

    @Test
    void platformCreatePassesOperatorAsActor() throws Exception {
        when(lifecycleService.create(any(), eq("ops-a"))).thenReturn(tenant(TenantStatus.PROVISIONING));

        mockMvc.perform(post("/platform/api/v1/tenants")
                        .header("X-Platform-Operator-Id", "ops-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"demo","name":"演示租户","planCode":"standard",
                                 "primaryDomain":"demo.example.test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("provisioning"));
    }

    private TenantRecord tenant(TenantStatus status) {
        return new TenantRecord(tenantId, "demo", "演示租户", status, "standard",
                OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, null);
    }
}
