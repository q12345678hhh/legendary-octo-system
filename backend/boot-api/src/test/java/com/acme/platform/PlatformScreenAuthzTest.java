package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.SessionService;
import com.acme.platform.identity.operator.PlatformActorContext;
import com.acme.platform.identity.operator.PlatformOpsProperties;
import com.acme.platform.identity.operator.PlatformOperatorService;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.audit.PlatformAuditStore;
import com.acme.platform.platform.catalog.PlatformCatalogService;
import com.acme.platform.platform.directory.PlatformDirectoryStore;
import com.acme.platform.platform.idempotency.IdempotencyService;
import com.acme.platform.platform.operations.PlatformOperationService;
import com.acme.platform.platform.operations.PlatformOperationStore;
import com.acme.platform.platform.overview.PlatformOverviewService;
import com.acme.platform.platform.security.PlatformSecurityService;
import com.acme.platform.platform.settings.PlatformSettingService;
import com.acme.platform.tenancy.TenantExecutionGate;
import com.acme.platform.tenancy.TenantLifecycleService;
import com.acme.platform.tenancy.TenantSupportAccessService;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * 六个运营界面的后端鉴权：无会话 401、缺权限 403、写操作 MFA 门禁，
 * 以及目录/总览响应不含租户业务正文字段。
 */
@WebMvcTest(controllers = {PlatformOverviewController.class, PlatformCatalogController.class,
        PlatformSecurityController.class, PlatformOperationController.class,
        PlatformSettingController.class, PlatformTenantController.class})
@Import(ProblemDetailsSupport.class)
class PlatformScreenAuthzTest {

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

    @MockitoBean
    private PlatformOverviewService overviewService;

    @MockitoBean
    private PlatformCatalogService catalogService;

    @MockitoBean
    private PlatformSecurityService securityService;

    @MockitoBean
    private PlatformOperationService operationService;

    @MockitoBean
    private PlatformSettingService settingService;

    @MockitoBean
    private TenantLifecycleService lifecycleService;

    @MockitoBean
    private PlatformDirectoryStore directoryStore;

    @MockitoBean
    private PlatformAuditStore auditStore;

    @Autowired
    private MockMvc mockMvc;

    private static final String OPS_COOKIE = "plt_ops_session";
    private static final String OPS_CSRF = "plt_ops_csrf";

    private final PlatformActorContext owner = actor("platform-owner", true);
    private final PlatformActorContext auditor = actor("platform-auditor", false);
    private final PlatformActorContext ops = actor("platform-ops", true);
    private final PlatformActorContext security = actor("platform-security", false);

    private static PlatformActorContext actor(String role, boolean mfa) {
        return new PlatformActorContext(UUID.randomUUID(), UUID.randomUUID(),
                role + "@example.test", role, role, mfa);
    }

    @BeforeEach
    void setUp() {
        when(opsProperties.getCookieName()).thenReturn(OPS_COOKIE);
        when(opsProperties.getCsrfCookieName()).thenReturn(OPS_CSRF);
        when(opsProperties.getCookiePath()).thenReturn("/platform");
        when(identityProperties.getCookieName()).thenReturn("plt_session");
        when(identityProperties.getCsrfCookieName()).thenReturn("plt_csrf");

        when(operatorService.authenticate("owner-token")).thenReturn(owner);
        when(operatorService.authenticate("auditor-token")).thenReturn(auditor);
        when(operatorService.authenticate("ops-token")).thenReturn(ops);
        when(operatorService.authenticate("security-token")).thenReturn(security);

        when(overviewService.overview()).thenReturn(new PlatformOverviewService.Overview(
                OffsetDateTime.now(), Map.of("active", 1L), 1, 1, 5, 1024, 0, 0, 0, 0,
                0.0, 0, 0, 0, 0, List.of(), null, true));
        when(catalogService.apps()).thenReturn(List.of());
        when(catalogService.connectors()).thenReturn(List.of());
        when(securityService.list(any(), any(), anyInt())).thenReturn(List.of());
        when(operationService.overview()).thenReturn(new PlatformOperationService.OperationsOverview(
                null, 0, 0, 0, 0, OffsetDateTime.now()));
        when(settingService.all()).thenReturn(List.of());
        when(directoryStore.list(any(), any(), anyInt())).thenReturn(List.of(
                new PlatformDirectoryStore.TenantSummary(UUID.randomUUID(), "demo", "演示租户",
                        "active", "standard", OffsetDateTime.now(), OffsetDateTime.now())));
    }

    private static Cookie session(String token) {
        return new Cookie(OPS_COOKIE, token);
    }

    private static Cookie csrf() {
        return new Cookie(OPS_CSRF, "csrf-token");
    }

    @Test
    void allSixScreensRequireOperatorSession() throws Exception {
        List<String> screens = List.of("/platform/api/v1/overview",
                "/platform/api/v1/tenants", "/platform/api/v1/catalog/apps",
                "/platform/api/v1/security/events", "/platform/api/v1/operations/overview",
                "/platform/api/v1/settings");
        for (String screen : screens) {
            mockMvc.perform(get(screen))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("PLATFORM_SESSION_REQUIRED"));
        }
    }

    @Test
    void auditorReadsEveryScreenButCannotWriteCatalog() throws Exception {
        mockMvc.perform(get("/platform/api/v1/overview").cookie(session("auditor-token")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/platform/api/v1/tenants").cookie(session("auditor-token")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/platform/api/v1/catalog/apps").cookie(session("auditor-token")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/platform/api/v1/security/events").cookie(session("auditor-token")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/platform/api/v1/operations/overview")
                        .cookie(session("auditor-token")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/platform/api/v1/settings").cookie(session("auditor-token")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/platform/api/v1/catalog/apps")
                        .cookie(session("auditor-token"), csrf())
                        .header("X-CSRF-Token", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appCode":"demo-app","appName":"演示应用","version":"1.0.0",
                                 "status":"published"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_PERMISSION_DENIED"));
    }

    @Test
    void opsCannotManageSettingsOrSecurity() throws Exception {
        mockMvc.perform(put("/platform/api/v1/settings/limits.max_tenants")
                        .cookie(session("ops-token"), csrf())
                        .header("X-CSRF-Token", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":30}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_PERMISSION_DENIED"));

        mockMvc.perform(post("/platform/api/v1/security/events")
                        .cookie(session("ops-token"), csrf())
                        .header("X-CSRF-Token", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"incident","severity":"high","summary":"验证用事件"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_PERMISSION_DENIED"));
    }

    @Test
    void securityWriteRequiresMfa() throws Exception {
        mockMvc.perform(post("/platform/api/v1/security/events")
                        .cookie(session("security-token"), csrf())
                        .header("X-CSRF-Token", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"incident","severity":"high","summary":"验证用事件"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_MFA_REQUIRED"));
    }

    @Test
    void ownerWithMfaCanUpdateSetting() throws Exception {
        when(settingService.put(eq("limits.max_tenants"), any(), any())).thenReturn(
                new PlatformSettingService.SettingView("limits.max_tenants", "limits",
                        new ObjectMapper().readTree("30"), false, true, "owner@example.test",
                        OffsetDateTime.now()));

        mockMvc.perform(put("/platform/api/v1/settings/limits.max_tenants")
                        .cookie(session("owner-token"), csrf())
                        .header("X-CSRF-Token", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("limits.max_tenants"))
                .andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    void tenantDirectoryResponseContainsNoBusinessFields() throws Exception {
        mockMvc.perform(get("/platform/api/v1/tenants").cookie(session("auditor-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("演示租户"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("file"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("person"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("worklog"))));
    }
}
