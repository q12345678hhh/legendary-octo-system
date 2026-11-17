package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AccessScope;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.DecisionReason;
import com.acme.platform.department.DepartmentWorkspaceService;
import com.acme.platform.department.insight.InsightService;
import com.acme.platform.department.insight.InsightStore;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.MemberStatus;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.worklog.access.WorkAccessLogService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 洞察 HTTP 契约：范围口径、阈值抑制后的空明细、设置写入权限。
 */
@WebMvcTest(controllers = InsightController.class)
@Import(ProblemDetailsSupport.class)
class InsightAuthzTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final String BEARER = "Bearer test-session-token";
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 13);

    @MockitoBean
    private InsightService insights;

    @MockitoBean
    private DepartmentWorkspaceService departments;

    @MockitoBean
    private AuthorizationGuard authorization;

    @MockitoBean
    private MemberStore memberStore;

    @MockitoBean
    private WorkAccessLogService accessLog;

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
                USER, VIEWER, MemberStatus.ACTIVE, false, false, false)));
    }

    @Test
    void tenantWideScopeReadsDepartmentOverview() throws Exception {
        when(authorization.require(eq("insight.work.read"), eq("insight.work"), any(), any()))
                .thenReturn(allowedTenant());
        when(insights.overview(UNIT, FROM, TO)).thenReturn(overview(false));

        mockMvc.perform(get("/api/v1/insights/work")
                        .param("orgUnitId", UNIT.toString())
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppressed").value(false))
                .andExpect(jsonPath("$.submittedPersonDays").value(30))
                .andExpect(jsonPath("$.threshold").value(5));
    }

    @Test
    void personScopeUsesSelfScopeWhenNoOrgUnit() throws Exception {
        Set<UUID> people = Set.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(authorization.require(eq("insight.work.read"), eq("insight.work"), any(), any()))
                .thenReturn(allowedPeople(people));
        when(insights.overviewForPeople(null, people, FROM, TO)).thenReturn(overview(false));

        mockMvc.perform(get("/api/v1/insights/work")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppressed").value(false));
    }

    @Test
    void drilldownFiltersUnauthorizedPeopleAndSuppressesSmallRemainder() throws Exception {
        List<InsightStore.PersonRow> rows = new ArrayList<>();
        List<UUID> people = new ArrayList<>();
        for (int index = 0; index < 6; index += 1) {
            UUID personId = UUID.randomUUID();
            people.add(personId);
            rows.add(new InsightStore.PersonRow(personId, "员工" + index, 3, 180));
        }

        when(authorization.require(eq("insight.work.read"), eq("insight.work"), any(), any()))
                .thenReturn(allowedTenant());
        when(insights.drilldown(UNIT, FROM, TO)).thenReturn(rows);
        when(insights.threshold()).thenReturn(5);

        UUID denied = people.get(5);
        when(authorization.decide(eq("worklog.time.read"), eq("worklog.time"), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2).equals(denied)
                        ? AccessDecision.deny(DecisionReason.NO_ALLOW, 1L,
                                OffsetDateTime.now(), List.of())
                        : allowedPerson(invocation.getArgument(2)));

        mockMvc.perform(get("/api/v1/insights/work/people")
                        .param("orgUnitId", UNIT.toString())
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(5));

        verify(accessLog, never()).record(any(), eq(denied), anyString(), any(), anyString(), any());
    }

    @Test
    void drilldownIsSuppressedWhenRemainingPeopleBelowThreshold() throws Exception {
        List<InsightStore.PersonRow> rows = new ArrayList<>();
        List<UUID> people = new ArrayList<>();
        for (int index = 0; index < 6; index += 1) {
            UUID personId = UUID.randomUUID();
            people.add(personId);
            rows.add(new InsightStore.PersonRow(personId, "员工" + index, 3, 180));
        }
        Set<UUID> allowedPeople = Set.copyOf(people.subList(0, 3));

        when(authorization.require(eq("insight.work.read"), eq("insight.work"), any(), any()))
                .thenReturn(allowedTenant());
        when(insights.drilldown(UNIT, FROM, TO)).thenReturn(rows);
        when(insights.threshold()).thenReturn(5);
        when(authorization.decide(eq("worklog.time.read"), eq("worklog.time"), any(), any()))
                .thenAnswer(invocation -> allowedPeople.contains(invocation.getArgument(2))
                        ? allowedPerson(invocation.getArgument(2))
                        : AccessDecision.deny(DecisionReason.NO_ALLOW, 1L,
                                OffsetDateTime.now(), List.of()));

        mockMvc.perform(get("/api/v1/insights/work/people")
                        .param("orgUnitId", UNIT.toString())
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threshold").value(5))
                .andExpect(jsonPath("$.rows.length()").value(0));
    }

    @Test
    void settingsWriteRequiresTenantAdmin() throws Exception {
        when(authorization.require(eq("tenant.admin"), eq("tenant"), any(), any()))
                .thenThrow(ApiException.forbidden("FORBIDDEN", "无权调整隐私阈值"));

        mockMvc.perform(put("/api/v1/insights/settings")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType("application/json")
                        .content("{\"minGroupSize\":8}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void thresholdCannotBeLowered() throws Exception {
        when(authorization.require(eq("tenant.admin"), eq("tenant"), any(), any()))
                .thenReturn(allowedTenant());
        when(insights.updateMinGroupSize(eq(3), any()))
                .thenThrow(ApiException.badRequest("INSIGHT_THRESHOLD_INVALID",
                        "最小群组阈值必须在 5–100 之间"));

        mockMvc.perform(put("/api/v1/insights/settings")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType("application/json")
                        .content("{\"minGroupSize\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSIGHT_THRESHOLD_INVALID"));
    }

    private AccessDecision allowedTenant() {
        return new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:business-owner"),
                AccessScope.tenant(), Map.of(), 1L, OffsetDateTime.now(), false);
    }

    private AccessDecision allowedPeople(Set<UUID> personIds) {
        return new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:manager"),
                new AccessScope(false, false, personIds, Set.of(), Set.of()),
                Map.of(), 1L, OffsetDateTime.now(), false);
    }

    private AccessDecision allowedPerson(UUID personId) {
        return new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:manager"),
                AccessScope.self(personId), Map.of(), 1L, OffsetDateTime.now(), false);
    }

    private InsightService.Overview overview(boolean suppressed) {
        return new InsightService.Overview(UNIT, FROM, TO, 5, suppressed,
                suppressed ? null : 6, suppressed ? null : 0.5,
                suppressed ? null : 30, suppressed ? null : 60,
                suppressed ? null : 1800L, suppressed ? null : 300,
                List.of(new InsightService.TaskStatusCount("todo", 2)),
                suppressed ? null : 1, new InsightService.ReportCounts(0, 1, 2, 0),
                List.of());
    }
}
