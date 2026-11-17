package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.authorization.AuthorizationGuard;
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
import com.acme.platform.tenancy.privacy.PrivacySettingsService;
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

/** 隐私中心 HTTP 边界：分类与设置要租户管理员，我的数据只返回本人。 */
@WebMvcTest(controllers = PrivacyController.class)
@Import(ProblemDetailsSupport.class)
class PrivacyApiTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000e1");
    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000a1");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PERSON = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000a1");
    private static final String BEARER = "Bearer test-session-token";

    @MockitoBean
    private PrivacyService privacy;

    @MockitoBean
    private PrivacySettingsService settings;

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
    }

    @Test
    void unauthenticatedMyDataIsRejected() throws Exception {
        when(sessionService.authenticateToken(any(), anyString())).thenReturn(null);

        mockMvc.perform(get("/api/v1/privacy/my-data")
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dataClassesRequireTenantAdmin() throws Exception {
        when(authorization.require(eq("tenant.admin"), eq("tenant"), isNull(), any()))
                .thenThrow(ApiException.forbidden("TENANT_ADMIN_REQUIRED", "需要租户管理员"));

        mockMvc.perform(get("/api/v1/privacy/data-classes")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_ADMIN_REQUIRED"));

        verify(privacy, never()).dataClasses();
    }

    @Test
    void myDataUsesSessionPersonOnly() throws Exception {
        when(privacy.myData(PERSON)).thenReturn(new PrivacyService.MyDataView(PERSON,
                List.of(), List.of()));

        mockMvc.perform(get("/api/v1/privacy/my-data")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personId").value(PERSON.toString()));

        verify(privacy).myData(PERSON);
    }

    @Test
    void deletePassesRequestedIdsForCurrentPerson() throws Exception {
        UUID voice = UUID.randomUUID();
        UUID expense = UUID.randomUUID();
        when(privacy.delete(eq(PERSON), any(), any())).thenReturn(new PrivacyService.DeleteResult(
                List.of(new PrivacyService.DeleteAction(voice, "worklog_voice_capture", "recycled"),
                        new PrivacyService.DeleteAction(expense, "expense_document", "anonymized"))));

        mockMvc.perform(post("/api/v1/privacy/my-data/delete")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voiceCaptureIds\":[\"" + voice
                                + "\"],\"expenseDocumentIds\":[\"" + expense + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].action").value("recycled"))
                .andExpect(jsonPath("$.actions[1].action").value("anonymized"));

        verify(privacy).delete(PERSON, List.of(voice), List.of(expense));
    }

    @Test
    void settingsUpdateRequiresAdminAndPassesInput() throws Exception {
        when(privacy.updateSettings(any())).thenReturn(new PrivacyService.SettingsView(
                "eu", false, 90, 365, "admin", OffsetDateTime.now(), List.of()));

        mockMvc.perform(put("/api/v1/privacy/settings")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataResidency\":\"eu\",\"trainingOptOut\":false,"
                                + "\"voiceTranscriptRetentionDays\":90,"
                                + "\"expenseOcrRetentionDays\":365}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataResidency").value("eu"));

        verify(authorization).require("tenant.admin", "tenant", null, Map.of());
        verify(privacy).updateSettings(new PrivacySettingsService.Input("eu", false, 90, 365));
    }

    @Test
    void unboundAccountCannotReadMyData() throws Exception {
        when(memberStore.findById(MEMBER)).thenReturn(Optional.of(new TenantMember(MEMBER, TENANT,
                USER, null, MemberStatus.ACTIVE, false, false, false)));

        mockMvc.perform(get("/api/v1/privacy/my-data")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERSON_NOT_BOUND"));
    }
}
