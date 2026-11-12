package com.acme.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AccessScope;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.DecisionReason;
import com.acme.platform.authorization.FieldAccess;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.MemberStatus;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.organization.DirectoryScope;
import com.acme.platform.organization.PeopleService;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 人员目录 HTTP 契约：范围下推、邮箱字段、CSV 导出与不存在不泄露。 */
@WebMvcTest(controllers = PeopleController.class)
@Import(ProblemDetailsSupport.class)
class PeopleAccessApiTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID VIEWER_PERSON = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();
    private static final String BEARER = "Bearer test-session-token";

    @MockitoBean
    private PeopleService peopleService;

    @MockitoBean
    private AuthorizationGuard authorization;

    @MockitoBean
    private MemberStore memberStore;

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
                        ACTOR, USER, MEMBER, "user", "admin@example.test", "管理员",
                        true, true, true, true));
        when(authorization.actor()).thenReturn(new ActorContext(TENANT, ACTOR, USER, MEMBER,
                "user", true, null));
        when(memberStore.findById(MEMBER)).thenReturn(Optional.of(new TenantMember(MEMBER, TENANT,
                USER, VIEWER_PERSON, MemberStatus.ACTIVE, false, false, false)));
        when(peopleService.directory(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new PeopleService.DirectoryEntry(PERSON, "张三", null,
                        "active", OffsetDateTime.now())));
    }

    @Test
    void listPushesDecisionScopeAndMasksEmail() throws Exception {
        when(authorization.require(anyString(), anyString(), any(), any())).thenReturn(decision());

        mockMvc.perform(get("/api/v1/people")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("张三"))
                .andExpect(jsonPath("$[0].primaryEmail")
                        .value(org.hamcrest.Matchers.nullValue()));

        ArgumentCaptor<DirectoryScope> captor = ArgumentCaptor.forClass(DirectoryScope.class);
        verify(peopleService).directory(captor.capture(), isNull(), eq(50));
        assertThat(captor.getValue().tenantWide()).isFalse();
        assertThat(captor.getValue().orgUnitIds()).containsExactly(UNIT);
        assertThat(captor.getValue().viewerPersonId()).isEqualTo(VIEWER_PERSON);
        assertThat(captor.getValue().emailVisible()).isFalse();
    }

    @Test
    void csvExportUsesExportDecisionAndAttachment() throws Exception {
        when(authorization.require(eq("directory.person.export"), anyString(), any(), any()))
                .thenReturn(decision());
        when(peopleService.export(any(), any())).thenReturn(List.of(
                new PeopleService.DirectoryEntry(PERSON, "张三", "zhang@example.test", "active",
                        OffsetDateTime.now())));

        mockMvc.perform(get("/api/v1/people").param("format", "csv")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("people.csv")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id,displayName,email,status")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "zhang@example.test")));

        verify(authorization).require(eq("directory.person.export"), eq("directory.person"),
                isNull(), any());
    }

    @Test
    void detailOutsideScopeReturnsNotFoundProblem() throws Exception {
        when(authorization.require(anyString(), anyString(), any(), any())).thenThrow(
                ApiException.notFound("PERSON_NOT_FOUND", "自然人不存在：" + PERSON));

        mockMvc.perform(get("/api/v1/people/{id}", PERSON)
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERSON_NOT_FOUND"));
    }

    private AccessDecision decision() {
        return new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:employee:SELF"),
                new AccessScope(false, false, Set.of(VIEWER_PERSON), Set.of(UNIT), Set.of()),
                Map.of("primaryEmail", FieldAccess.none()), 1L, OffsetDateTime.now(), false);
    }
}
