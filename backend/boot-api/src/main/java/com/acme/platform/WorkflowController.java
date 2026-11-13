package com.acme.platform;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.workflow.WorkflowDefinitionService;
import com.acme.platform.workflow.WorkflowExceptionService;
import com.acme.platform.workflow.WorkflowInstanceService;
import com.acme.platform.workflow.WorkflowTaskService;
import com.acme.platform.workflow.WorkflowViews;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通用工作流与审批。
 *
 * <p>定义管理只提供 API，不做可视化编排器；审批人、发起人一律从会话解析，
 * 忽略请求体中的任何主体标识。直接访问始终由后端逐请求鉴权。
 */
@RestController
@RequestMapping("/api/v1/workflow")
class WorkflowController {

    private final WorkflowDefinitionService definitions;
    private final WorkflowInstanceService instances;
    private final WorkflowTaskService tasks;
    private final WorkflowExceptionService exceptions;
    private final AuthorizationGuard authorization;
    private final MemberStore memberStore;

    WorkflowController(WorkflowDefinitionService definitions, WorkflowInstanceService instances,
                       WorkflowTaskService tasks, WorkflowExceptionService exceptions,
                       AuthorizationGuard authorization, MemberStore memberStore) {
        this.definitions = definitions;
        this.instances = instances;
        this.tasks = tasks;
        this.exceptions = exceptions;
        this.authorization = authorization;
        this.memberStore = memberStore;
    }

    record DefinitionRequest(String key, String name, String schema) {
    }

    record StartRequest(String definitionKey, String businessType, UUID businessId,
                        String businessKey, Map<String, Object> variables) {
    }

    record TaskActionRequest(String action, String comment) {
    }

    record TransferRequest(UUID targetPersonId, String comment) {
    }

    record ResolveRequest(String action, UUID assigneePersonId) {
    }

    // ------------------------------------------------------------------ 定义

    @GetMapping("/definitions")
    List<WorkflowDefinitionService.DefinitionView> definitions() {
        require("workflow.definition.manage", "workflow.definition", null, Map.of());
        return definitions.list();
    }

    @PostMapping("/definitions")
    WorkflowDefinitionService.DefinitionView createDefinition(
            @RequestBody DefinitionRequest request) {
        require("workflow.definition.manage", "workflow.definition", null, Map.of());
        return definitions.createDraft(request.key(), request.name(), request.schema(),
                viewerPersonId().map(UUID::toString).orElse(null));
    }

    @PutMapping("/definitions/{definitionId}")
    WorkflowDefinitionService.DefinitionView updateDefinition(
            @PathVariable UUID definitionId, @RequestBody DefinitionRequest request) {
        require("workflow.definition.manage", "workflow.definition", definitionId,
                Map.of("definitionId", definitionId));
        return definitions.updateDraft(definitionId, request.name(), request.schema());
    }

    @PostMapping("/definitions/{definitionId}/publish")
    WorkflowDefinitionService.DefinitionView publishDefinition(@PathVariable UUID definitionId) {
        require("workflow.definition.manage", "workflow.definition", definitionId,
                Map.of("definitionId", definitionId));
        return definitions.publish(definitionId);
    }

    @PostMapping("/definitions/{definitionId}/retire")
    WorkflowDefinitionService.DefinitionView retireDefinition(@PathVariable UUID definitionId) {
        require("workflow.definition.manage", "workflow.definition", definitionId,
                Map.of("definitionId", definitionId));
        return definitions.retire(definitionId);
    }

    // ------------------------------------------------------------------ 实例

    @PostMapping("/instances")
    WorkflowViews.InstanceView start(@RequestBody StartRequest request) {
        UUID personId = requirePerson();
        require("workflow.instance.start", "workflow.instance", null,
                Map.of("personId", personId));
        return instances.start(request.definitionKey(), request.businessType(),
                request.businessId(), request.businessKey(), request.variables(), personId);
    }

    @GetMapping("/instances/{instanceId}")
    WorkflowViews.InstanceDetail instance(@PathVariable UUID instanceId) {
        WorkflowViews.InstanceDetail detail = instances.detail(instanceId);
        UUID personId = viewerPersonId().orElse(null);
        boolean admin = isAdmin(instanceId);
        boolean participant = personId != null
                && (personId.equals(detail.instance().startedByPersonId())
                || detail.tasks().stream().anyMatch(task -> personId.equals(task.assigneePersonId())));
        if (!admin && !participant) {
            throw ApiException.forbidden("WORKFLOW_NOT_PARTICIPANT", "只能查看自己参与或发起的流程");
        }
        return detail;
    }

    @PostMapping("/instances/{instanceId}/withdraw")
    WorkflowViews.InstanceView withdraw(@PathVariable UUID instanceId) {
        UUID personId = requirePerson();
        require("workflow.instance.start", "workflow.instance", instanceId,
                Map.of("personId", personId));
        return instances.withdraw(instanceId, personId);
    }

    @PostMapping("/instances/{instanceId}/cancel")
    WorkflowViews.InstanceView cancel(@PathVariable UUID instanceId) {
        UUID personId = requirePerson();
        require("workflow.instance.admin", "workflow.instance", instanceId,
                Map.of("instanceId", instanceId));
        return instances.cancel(instanceId, personId);
    }

    // ------------------------------------------------------------------ 审批箱

    @GetMapping("/tasks")
    List<WorkflowViews.TaskView> tasks(@RequestParam(defaultValue = "mine") String scope) {
        UUID personId = requirePerson();
        boolean admin = requireTaskAccess(null);
        if ("all".equals(scope)) {
            return tasks.inboxForScope(personId, admin, java.util.Set.of());
        }
        return tasks.inboxForPerson(personId);
    }

    @PostMapping("/tasks/{taskId}/claim")
    WorkflowViews.TaskView claim(@PathVariable UUID taskId) {
        UUID personId = requirePerson();
        boolean admin = requireTaskAccess(taskId);
        return tasks.claim(taskId, personId, admin);
    }

    @PostMapping("/tasks/{taskId}/complete")
    WorkflowViews.TaskView complete(@PathVariable UUID taskId,
                                    @RequestBody TaskActionRequest request) {
        UUID personId = requirePerson();
        boolean admin = requireTaskAccess(taskId);
        return tasks.complete(taskId, personId, request.action(), request.comment(), admin);
    }

    @PostMapping("/tasks/{taskId}/transfer")
    WorkflowViews.TaskView transfer(@PathVariable UUID taskId,
                                    @RequestBody TransferRequest request) {
        UUID personId = requirePerson();
        boolean admin = requireTaskAccess(taskId);
        return tasks.transfer(taskId, personId, request.targetPersonId(), request.comment(), admin);
    }

    // ------------------------------------------------------------------ 异常

    @GetMapping("/exceptions")
    List<WorkflowViews.ExceptionView> exceptions() {
        require("workflow.instance.admin", "workflow.exception", null, Map.of());
        return exceptions.list();
    }

    @PostMapping("/exceptions/{exceptionId}/resolve")
    WorkflowViews.ExceptionView resolve(@PathVariable UUID exceptionId,
                                        @RequestBody ResolveRequest request) {
        UUID personId = requirePerson();
        require("workflow.instance.admin", "workflow.exception", exceptionId,
                Map.of("instanceId", exceptionId));
        return exceptions.resolve(exceptionId, personId, request.action(),
                request.assigneePersonId());
    }

    // ------------------------------------------------------------------ 内部

    private AccessDecision require(String permission, String resourceType, UUID resourceId,
                                   Map<String, Object> attributes) {
        return authorization.require(permission, resourceType, resourceId, attributes);
    }

    /** 返回是否租户级管理员；普通处理人凭 workflow.task.act 通过。 */
    private boolean requireTaskAccess(UUID taskId) {
        AccessDecision task = authorization.decide("workflow.task.act", "workflow.task",
                taskId, Map.of());
        if (task.allowed()) {
            return task.scope().tenantWide();
        }
        AccessDecision admin = authorization.decide("workflow.instance.admin", "workflow.task",
                taskId, Map.of());
        if (admin.allowed()) {
            return true;
        }
        throw ApiException.forbidden(task.reason().code(), task.reason().message());
    }

    private boolean isAdmin(UUID instanceId) {
        return authorization.decide("workflow.instance.admin", "workflow.instance",
                instanceId, Map.of()).allowed();
    }

    private UUID requirePerson() {
        return viewerPersonId().orElseThrow(() ->
                ApiException.forbidden("PERSON_NOT_BOUND", "当前账号未绑定员工"));
    }

    private Optional<UUID> viewerPersonId() {
        ActorContext actor = authorization.actor();
        if (actor == null || actor.memberId() == null) {
            return Optional.empty();
        }
        return memberStore.findById(actor.memberId())
                .map(TenantMember::personId)
                .filter(java.util.Objects::nonNull);
    }
}
