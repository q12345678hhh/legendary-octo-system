package com.acme.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AccessScope;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.DecisionReason;
import com.acme.platform.identity.MemberStatus;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.SessionPrincipal;
import com.acme.platform.identity.SessionService;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.idempotency.IdempotencyService;
import com.acme.platform.workflow.WorkflowDefinitionService;
import com.acme.platform.workflow.WorkflowExceptionService;
import com.acme.platform.workflow.WorkflowInstanceService;
import com.acme.platform.workflow.WorkflowTaskService;
import com.acme.platform.workflow.WorkflowViews;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 工作流 HTTP 鉴权：定义管理、发起、审批箱与任务动作都必须在服务调用前拒绝越权。
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ProblemDetailsSupport.class)
class WorkflowAuthzTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();
    private static final String BEARER = "Bearer test-session-token";

    @MockitoBean
    private WorkflowDefinitionService definitions;

    @MockitoBean
    private WorkflowInstanceService instances;

    @MockitoBean
    private WorkflowTaskService tasks;

    @MockitoBean
    private WorkflowExceptionService exceptions;

    @MockitoBean
    private AuthorizationGuard authorization;

    @MockitoBean
    private MemberStore memberStore;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private com.acme.platform.identity.IdentityProperties identityProperties;

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
        when(authorization.decide(any(), any(), any(), any())).thenReturn(allowed());
    }

    @Test
    void employeeCannotCreateDefinition() throws Exception {
        when(authorization.require(eq("workflow.definition.manage"), eq("workflow.definition"),
                isNull(), any())).thenThrow(ApiException.forbidden("FORBIDDEN", "无权管理定义"));

        mockMvc.perform(post("/api/v1/workflow/definitions")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"demo.leave","name":"请假","schema":"{}"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void inboxReturnsOnlyTasksAssignedToViewer() throws Exception {
        when(tasks.inboxForPerson(PERSON)).thenReturn(List.of(task()));

        mockMvc.perform(get("/api/v1/workflow/tasks")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assigneePersonId").value(PERSON.toString()));
    }

    @Test
    void nonAssigneeCannotCompleteTask() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(tasks.complete(eq(taskId), eq(PERSON), eq("approve"), any(), eq(false)))
                .thenThrow(ApiException.forbidden("WORKFLOW_NOT_ASSIGNEE", "只能处理分配给自己的任务"));

        mockMvc.perform(post("/api/v1/workflow/tasks/{taskId}/complete", taskId)
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"approve","comment":"同意"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKFLOW_NOT_ASSIGNEE"));
    }

    @Test
    void unboundAccountCannotStartInstance() throws Exception {
        when(memberStore.findById(MEMBER)).thenReturn(Optional.of(new TenantMember(MEMBER, TENANT,
                USER, null, MemberStatus.ACTIVE, false, false, false)));

        mockMvc.perform(post("/api/v1/workflow/instances")
                        .header("Authorization", BEARER)
                        .header("X-Tenant-Id", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"definitionKey":"demo.leave","businessType":"generic"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERSON_NOT_BOUND"));
    }

    private static WorkflowViews.TaskView task() {
        return new WorkflowViews.TaskView(UUID.randomUUID(), null, null, "worklog.time",
                UUID.randomUUID(), "review", "工时待审核", PERSON, "direct", "pending", null, null,
                null, "normal", OffsetDateTime.now());
    }

    private AccessDecision allowed() {
        return new AccessDecision(true, DecisionReason.ALLOWED, List.of("GRANT:employee:ORG_UNIT"),
                new AccessScope(false, false, Set.of(), Set.of(PERSON), Set.of()),
                Map.of(), 1L, OffsetDateTime.now(), false);
    }
}
