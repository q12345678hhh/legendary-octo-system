package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.SessionService;
import com.acme.platform.identity.operator.PlatformActorContext;
import com.acme.platform.identity.operator.PlatformOpsProperties;
import com.acme.platform.identity.operator.PlatformOperatorService;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.idempotency.IdempotencyService;
import com.acme.platform.platform.retention.DeletionRequest;
import com.acme.platform.platform.retention.DeletionRequestService;
import com.acme.platform.platform.settings.PlatformSettingService;
import com.acme.platform.tenancy.TenantExecutionGate;
import com.acme.platform.tenancy.retention.RetentionService;
import jakarta.servlet.http.Cookie;
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
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * WP02-08 的鉴权边界：租户侧必须登录，平台侧读要权限、写要 manage + MFA。
 */
@WebMvcTest(controllers = {RetentionController.class, PrivacyRequestController.class,
        PlatformRetentionController.class})
@Import(ProblemDetailsSupport.class)
class RetentionAuthzTest {

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
    private RetentionService retentionService;

    @MockitoBean
    private PlatformSettingService settingService;

    @MockitoBean
    private DeletionRequestService deletionRequestService;

    @MockitoBean
    private DataSubjectRequestOrchestrator orchestrator;

    @MockitoBean
    private TombstoneReplayService replayService;

    @Autowired
    private MockMvc mockMvc;

    private static final String OPS_COOKIE = "plt_ops_session";
    private static final String OPS_CSRF = "plt_ops_csrf";

    private final PlatformActorContext owner = actor("platform-owner", true);
    private final PlatformActorContext auditor = actor("platform-auditor", false);
    private final PlatformActorContext securityNoMfa = actor("platform-security", false);
    private final PlatformActorContext securityMfa = actor("platform-security", true);

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
        when(operatorService.authenticate("security-token")).thenReturn(securityNoMfa);
        when(operatorService.authenticate("security-mfa-token")).thenReturn(securityMfa);

        when(settingService.all()).thenReturn(List.of(new PlatformSettingService.SettingView(
                "retention.recycle_bin_days", "retention",
                JsonNodeFactory.instance.numberNode(30), false, true, "migration", OffsetDateTime.now())));
        when(deletionRequestService.listByStatus(any(), anyInt())).thenReturn(List.of());
        when(deletionRequestService.get(any())).thenReturn(request("approved"));
        when(deletionRequestService.approve(any(), any(), any(), any())).thenReturn(request("approved"));
    }

    private static DeletionRequest request(String status) {
        return new DeletionRequest(UUID.randomUUID(), UUID.randomUUID(), "person", UUID.randomUUID(),
                "a".repeat(64), "员工申请删除", status, "APPROVED", null, "tester",
                OffsetDateTime.now(), null, null, null, null, null);
    }

    private static Cookie session(String token) {
        return new Cookie(OPS_COOKIE, token);
    }

    private static Cookie csrf() {
        return new Cookie(OPS_CSRF, "csrf-token");
    }

    @Test
    void tenantRetentionRequiresSession() throws Exception {
        mockMvc.perform(get("/api/v1/retention/bin"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        mockMvc.perform(delete("/api/v1/retention/bin/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void platformRetentionRequiresOperatorSession() throws Exception {
        mockMvc.perform(get("/platform/api/v1/retention/policy"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLATFORM_SESSION_REQUIRED"));
        mockMvc.perform(get("/platform/api/v1/privacy/requests"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/platform/api/v1/retention/tombstones/replay"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void auditorCanReadPolicyButCannotDecideRequests() throws Exception {
        mockMvc.perform(get("/platform/api/v1/retention/policy").cookie(session("auditor-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("retention.recycle_bin_days"));

        mockMvc.perform(get("/platform/api/v1/privacy/requests").cookie(session("auditor-token")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/platform/api/v1/privacy/requests/" + UUID.randomUUID() + "/approve")
                        .cookie(session("auditor-token"), csrf())
                        .header("X-CSRF-Token", "csrf-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void securityManageWithoutMfaIsBlocked() throws Exception {
        mockMvc.perform(post("/platform/api/v1/privacy/requests/" + UUID.randomUUID() + "/approve")
                        .cookie(session("security-token"), csrf())
                        .header("X-CSRF-Token", "csrf-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_MFA_REQUIRED"));
    }

    @Test
    void securityManageWithMfaCanApproveAndReplay() throws Exception {
        mockMvc.perform(post("/platform/api/v1/privacy/requests/" + UUID.randomUUID() + "/approve")
                        .cookie(session("security-mfa-token"), csrf())
                        .header("X-CSRF-Token", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionCode\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));

        when(replayService.replay(anyInt(), any()))
                .thenReturn(new TombstoneReplayService.ReplayResult(1, 2, 0));
        mockMvc.perform(post("/platform/api/v1/retention/tombstones/replay")
                        .cookie(session("security-mfa-token"), csrf())
                        .header("X-CSRF-Token", "csrf-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1));
    }

    @Test
    void policyUpdateRejectsKeysOutsideRetention() throws Exception {
        mockMvc.perform(put("/platform/api/v1/retention/policy")
                        .cookie(session("owner-token"), csrf())
                        .header("X-CSRF-Token", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"limits.max_tenants\",\"value\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETENTION_POLICY_KEY_INVALID"));
    }
}
