package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.identity.IdentityGuard;
import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.SessionPrincipal;
import com.acme.platform.identity.SessionService;
import com.acme.platform.organization.OrganizationChange;
import com.acme.platform.organization.OrganizationChangeService;
import com.acme.platform.organization.OrganizationRelationService;
import com.acme.platform.organization.OrganizationReadiness;
import com.acme.platform.organization.OrganizationStructureService;
import com.acme.platform.organization.OrgUnit;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.idempotency.IdempotencyService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 组织结构 HTTP 契约：树形返回、移动票据门禁、批量上限与线上枚举格式。 */
@WebMvcTest(controllers = {OrganizationStructureController.class, OrganizationAssignmentController.class,
        OrganizationOwnerController.class, OrganizationChangeController.class})
@Import(ProblemDetailsSupport.class)
class OrganizationApiTest {

    private static final UUID TENANT = UUID.fromString("eeeeeeee-0000-4000-8000-000000000005");
    private static final UUID UNIT = UUID.fromString("e0000000-0000-4000-8000-000000000201");

    @MockitoBean
    private OrganizationStructureService structureService;

    @MockitoBean
    private OrganizationRelationService relationService;

    @MockitoBean
    private OrganizationChangeService changeService;

    @MockitoBean
    private IdentityGuard guard;

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

    private static final String BEARER = "Bearer test-session-token";

    @BeforeEach
    void authenticateRequests() {
        when(identityProperties.getCookieName()).thenReturn("plt_session");
        when(identityProperties.getCsrfCookieName()).thenReturn("plt_csrf");
        when(sessionService.authenticateToken(any(), anyString())).thenReturn(
                new SessionPrincipal(TENANT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), "user", "admin@example.test", "管理员",
                        true, true, true, true));
    }

    private static OrgUnit unit(UUID id, UUID parentId, String code, String name, OrgUnit.UnitType type) {
        return new OrgUnit(id, TENANT, parentId, UUID.fromString("e0000000-0000-4000-8000-000000000101"),
                code, name, type, OrgUnit.UnitStatus.ACTIVE, 0, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void treeReturnsNestedNodes() throws Exception {
        OrgUnit root = unit(UNIT, null, "C-ROOT", "总部", OrgUnit.UnitType.COMPANY);
        OrgUnit department = unit(UUID.randomUUID(), UNIT, "C-DEPT", "平台组",
                OrgUnit.UnitType.DEPARTMENT);
        when(structureService.unitTree()).thenReturn(List.of(
                new OrganizationStructureService.OrgUnitNode(root,
                        List.of(new OrganizationStructureService.OrgUnitNode(department, List.of())))));

        mockMvc.perform(get("/api/v1/org-units/tree").header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unit.code").value("C-ROOT"))
                .andExpect(jsonPath("$[0].unit.unitType").value("company"))
                .andExpect(jsonPath("$[0].children[0].unit.code").value("C-DEPT"))
                .andExpect(jsonPath("$[0].children[0].children").isEmpty());
    }

    @Test
    void moveWithoutPreviewTicketIsRejected() throws Exception {
        when(structureService.moveUnit(eq(UNIT), any())).thenThrow(
                ApiException.conflict("ORG_IMPACT_PREVIEW_REQUIRED", "移动部门前必须先申请影响预览"));

        mockMvc.perform(post("/api/v1/org-units/{unitId}/move", UNIT)
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetParentId\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORG_IMPACT_PREVIEW_REQUIRED"));
    }

    @Test
    void applyDueRejectsOutOfRangeBatch() throws Exception {
        mockMvc.perform(post("/api/v1/organization/changes/apply-due")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchSize\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_SIZE_INVALID"));

        verify(changeService, never()).applyDue(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void readinessReportsBlockers() throws Exception {
        when(relationService.readiness()).thenReturn(new OrganizationRelationService.ReadinessView(
                false, List.of(OrganizationReadiness.Blocker.OWNER_ROLE_MISSING,
                        OrganizationReadiness.Blocker.TRUSTEE_MISSING), List.of(), 0, 0));

        mockMvc.perform(get("/api/v1/organization/readiness").header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.blockers[0]").value("owner_role_missing"));
    }

    @Test
    void unknownOwnerRoleTypeIsUnprocessable() throws Exception {
        mockMvc.perform(post("/api/v1/owner-roles")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employmentId\":\"" + UUID.randomUUID()
                                + "\",\"roleType\":\"chairman\",\"effectiveFrom\":\"2026-01-01\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OWNER_ROLE_TYPE_INVALID"));
    }

    @Test
    void changeListUsesWireValues() throws Exception {
        OrganizationChange change = new OrganizationChange(UUID.randomUUID(), TENANT,
                OrganizationChange.ChangeType.UNIT_MOVE, UNIT, LocalDate.parse("2026-12-01"),
                Map.of("targetParentId", UNIT.toString()), OrganizationChange.ChangeStatus.SCHEDULED,
                "tester", "规划", null, null, OffsetDateTime.parse("2026-11-11T00:00:00Z"));
        when(changeService.list(eq(OrganizationChange.ChangeStatus.SCHEDULED), eq(UNIT)))
                .thenReturn(List.of(change));

        mockMvc.perform(get("/api/v1/organization/changes")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .param("status", "scheduled")
                        .param("targetId", UNIT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].changeType").value("unit_move"))
                .andExpect(jsonPath("$[0].status").value("scheduled"));
    }
}
