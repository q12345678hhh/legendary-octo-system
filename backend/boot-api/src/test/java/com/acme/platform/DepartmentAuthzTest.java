package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AccessScope;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.DecisionReason;
import com.acme.platform.department.DepartmentSpace;
import com.acme.platform.department.DepartmentSpaceStore;
import com.acme.platform.department.DepartmentWorkspaceService;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.MemberStatus;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.work.WorkStatusQuery;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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
 * 部门工作区 HTTP 契约：范围下推、无权字段整体省略、设置写入需要 manage 权限。
 */
@WebMvcTest(controllers = DepartmentController.class)
@Import(ProblemDetailsSupport.class)
class DepartmentAuthzTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID SELF_EMPLOYMENT = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID OTHER_EMPLOYMENT = UUID.randomUUID();
    private static final String BEARER = "Bearer test-session-token";

    @MockitoBean
    private DepartmentWorkspaceService service;

    @MockitoBean
    private AuthorizationGuard authorization;

    @MockitoBean
    private MemberStore memberStore;

    @MockitoBean
    private OrganizationScopeQueries organizationScopes;

    @MockitoBean
    private DepartmentFileCountQuery fileCounts;

    @MockitoBean
    private WorkStatusQuery workStatus;

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
        when(organizationScopes.scopeOfPerson(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void listUsesOrgUnitScopeFromDecision() throws Exception {
        when(authorization.require(eq("department.workspace.read"), eq("department.workspace"),
                any(), any())).thenReturn(allowedUnits());
        when(service.spaces(eq(false), eq(Set.of(UNIT)))).thenReturn(List.of(space()));

        mockMvc.perform(get("/api/v1/departments")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orgUnitId").value(UNIT.toString()))
                .andExpect(jsonPath("$[0].path").value("#/departments/" + UNIT));
    }

    @Test
    void unauthorizedCellsAreOmittedNotMasked() throws Exception {
        when(authorization.require(anyString(), anyString(), any(), any())).thenReturn(allowedUnits());
        when(service.requireWorkspace(UNIT)).thenReturn(workspace());
        when(workStatus.statuses(any(), any())).thenReturn(Map.of());
        when(fileCounts.counts(any())).thenReturn(Map.of(VIEWER, 2));

        allowFor(VIEWER, true);
        allowFor(OTHER, false);

        mockMvc.perform(get("/api/v1/departments/{id}/workspace", UNIT)
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].personId").value(VIEWER.toString()))
                .andExpect(jsonPath("$.members[0].workPageAccessible").value(true))
                .andExpect(jsonPath("$.members[0].workPagePath").value("#/work/" + VIEWER))
                .andExpect(jsonPath("$.members[0].authorizedFileCount").value(2))
                .andExpect(jsonPath("$.members[1].personId").value(OTHER.toString()))
                .andExpect(jsonPath("$.members[1].workPageAccessible").value(false))
                .andExpect(jsonPath("$.members[1].workPagePath").doesNotExist())
                .andExpect(jsonPath("$.members[1].todayMinutes").doesNotExist())
                .andExpect(jsonPath("$.members[1].dayState").doesNotExist())
                .andExpect(jsonPath("$.members[1].weekReportState").doesNotExist())
                .andExpect(jsonPath("$.members[1].authorizedFileCount").doesNotExist());
    }

    @Test
    void settingsRequireManagePermission() throws Exception {
        when(authorization.require(eq("department.workspace.manage"), eq("department.workspace"),
                any(), any())).thenThrow(ApiException.forbidden("FORBIDDEN", "无权维护部门设置"));

        mockMvc.perform(put("/api/v1/departments/{id}/settings", UNIT)
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType("application/json")
                        .content("{\"dayEndTime\":\"18:00\",\"weekReportDueWeekday\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private void allowFor(UUID personId, boolean allowed) {
        AccessDecision decision = allowed
                ? new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:self"),
                new AccessScope(false, false, Set.of(personId), Set.of(UNIT), Set.of()),
                Map.of(), 1L, OffsetDateTime.now(), false)
                : AccessDecision.deny(DecisionReason.NO_ALLOW, 1L,
                OffsetDateTime.now(), List.of());
        when(authorization.decide(anyString(), anyString(), eq(personId), any()))
                .thenReturn(decision);
    }

    private AccessDecision allowedUnits() {
        return new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:employee:ORG_UNIT"),
                new AccessScope(false, false, Set.of(), Set.of(UNIT), Set.of()),
                Map.of(), 1L, OffsetDateTime.now(), false);
    }

    private DepartmentSpace space() {
        return new DepartmentSpace(UUID.randomUUID(), UNIT, "研发部", "active",
                OffsetDateTime.now(), null);
    }

    private DepartmentWorkspaceService.Workspace workspace() {
        return new DepartmentWorkspaceService.Workspace(space(),
                new DepartmentSpaceStore.Setting(LocalTime.of(18, 0), 5),
                List.of(
                        new DepartmentSpaceStore.MemberRow(SELF_EMPLOYMENT, VIEWER, "张三",
                                "后端工程师", "后端工程师", "primary", null, 1),
                        new DepartmentSpaceStore.MemberRow(OTHER_EMPLOYMENT, OTHER, "李四",
                                "后端工程师", "后端工程师", "primary", "张三", 2)));
    }
}
