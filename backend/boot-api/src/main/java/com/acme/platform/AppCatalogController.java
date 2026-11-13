package com.acme.platform;

import com.acme.platform.appcatalog.AppCatalogService;
import com.acme.platform.appcatalog.AppCatalogViews;
import com.acme.platform.appcatalog.ModuleSuggestionStateMachine;
import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块目录与模块建议。
 *
 * <p>全员可发起建议；受理、驳回、最终启用等决定必须由租户级复核人执行，
 * 没有老板/授权复核人时直接阻断，不进入默认通过。发起人一律从会话解析。
 */
@RestController
@RequestMapping("/api/v1/app-catalog")
class AppCatalogController {

    private final AppCatalogService service;
    private final AuthorizationGuard authorization;
    private final MemberStore memberStore;

    AppCatalogController(AppCatalogService service, AuthorizationGuard authorization,
                         MemberStore memberStore) {
        this.service = service;
        this.authorization = authorization;
        this.memberStore = memberStore;
    }

    record ModuleRequest(String code, String name, String category, String description,
                         String templateKey, UUID ownerPersonId, String version,
                         String dependencies, String applicableScope, String healthStatus,
                         String installStatus) {

        AppCatalogService.ModuleInput toInput() {
            return new AppCatalogService.ModuleInput(code, name, category, description,
                    templateKey, ownerPersonId, version, dependencies, applicableScope,
                    healthStatus, installStatus);
        }
    }

    record SuggestionRequest(String title, String businessProblem, String goal, String scope,
                             String dataScope, String permissionsNeeded, String costEstimate,
                             String benefitEstimate, UUID suggestedOwnerPersonId) {

        AppCatalogService.SuggestionInput toInput() {
            return new AppCatalogService.SuggestionInput(title, businessProblem, goal, scope,
                    dataScope, permissionsNeeded, costEstimate, benefitEstimate,
                    suggestedOwnerPersonId);
        }
    }

    record ActionRequest(String action, String comment, UUID configOwnerPersonId) {

        AppCatalogService.ActionInput toInput() {
            return new AppCatalogService.ActionInput(action, comment, configOwnerPersonId);
        }
    }

    // ------------------------------------------------------------------ 目录

    @GetMapping("/modules")
    List<AppCatalogViews.ModuleView> modules(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) String category,
                                             @RequestParam(required = false) String health) {
        require("appcatalog.module.read", "appcatalog.module", null, Map.of());
        return service.listModules(q, category, health);
    }

    @GetMapping("/modules/{moduleId}")
    AppCatalogViews.ModuleView module(@PathVariable UUID moduleId) {
        require("appcatalog.module.read", "appcatalog.module", moduleId,
                Map.of("moduleId", moduleId));
        return service.getModule(moduleId);
    }

    @PostMapping("/modules")
    AppCatalogViews.ModuleView createModule(@RequestBody ModuleRequest request) {
        require("appcatalog.module.manage", "appcatalog.module", null, Map.of());
        return service.createModule(request.toInput());
    }

    @RequestMapping(value = "/modules/{moduleId}", method = RequestMethod.PUT)
    AppCatalogViews.ModuleView updateModule(@PathVariable UUID moduleId,
                                            @RequestBody ModuleRequest request) {
        require("appcatalog.module.manage", "appcatalog.module", moduleId,
                Map.of("moduleId", moduleId));
        return service.updateModule(moduleId, request.toInput());
    }

    // ------------------------------------------------------------------ 建议

    @GetMapping("/suggestions")
    List<AppCatalogViews.SuggestionView> suggestions(
            @RequestParam(defaultValue = "mine") String scope) {
        if ("all".equals(scope)) {
            requireReviewer();
            return service.listSuggestions(null, true);
        }
        UUID personId = requirePerson();
        require("appcatalog.suggestion.create", "appcatalog.suggestion", null,
                Map.of("personId", personId));
        return service.listSuggestions(personId, false);
    }

    @GetMapping("/suggestions/{suggestionId}")
    AppCatalogViews.SuggestionDetail suggestion(@PathVariable UUID suggestionId) {
        UUID personId = viewerPersonId().orElse(null);
        boolean reviewer = reviewerDecision(suggestionId).allowed();
        boolean creator = personId != null
                && authorization.decide("appcatalog.suggestion.create", "appcatalog.suggestion",
                suggestionId, Map.of("personId", personId)).allowed();
        if (!reviewer && !creator) {
            throw ApiException.forbidden("APP_SUGGESTION_FORBIDDEN", "无权查看该模块建议");
        }
        AppCatalogViews.SuggestionDetail detail = service.getSuggestion(suggestionId);
        if (!reviewer) {
            UUID requester = detail.suggestion().requesterPersonId();
            if (personId == null || !personId.equals(requester)) {
                throw ApiException.forbidden("APP_SUGGESTION_FORBIDDEN", "只能查看自己发起的建议");
            }
        }
        return detail;
    }

    @PostMapping("/suggestions")
    AppCatalogViews.SuggestionView createSuggestion(@RequestBody SuggestionRequest request) {
        UUID personId = requirePerson();
        require("appcatalog.suggestion.create", "appcatalog.suggestion", null,
                Map.of("personId", personId));
        return service.createSuggestion(request.toInput(), personId);
    }

    @PostMapping("/suggestions/{suggestionId}/actions")
    AppCatalogViews.SuggestionDetail act(@PathVariable UUID suggestionId,
                                         @RequestBody ActionRequest request) {
        UUID personId = requirePerson();
        String action = request.action() == null ? "" : request.action();
        if (ModuleSuggestionStateMachine.requesterActions().contains(action)) {
            require("appcatalog.suggestion.create", "appcatalog.suggestion", suggestionId,
                    Map.of("personId", personId));
        } else {
            requireReviewer();
        }
        return service.act(suggestionId, request.toInput(), personId);
    }

    // ------------------------------------------------------------------ 内部

    private AccessDecision require(String permission, String resourceType, UUID resourceId,
                                   Map<String, Object> attributes) {
        return authorization.require(permission, resourceType, resourceId, attributes);
    }

    /** 受理/启用等决定必须由租户级复核人执行；无复核人时 403，不自动通过。 */
    private AccessDecision requireReviewer() {
        AccessDecision decision = reviewerDecision(null);
        if (!decision.allowed() || !decision.scope().tenantWide()) {
            throw ApiException.forbidden("APP_SUGGESTION_REVIEWER_UNAVAILABLE",
                    "当前租户没有可受理模块建议的老板或授权复核人");
        }
        return decision;
    }

    private AccessDecision reviewerDecision(UUID resourceId) {
        return authorization.decide("appcatalog.suggestion.review", "appcatalog.suggestion",
                resourceId, Map.of());
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
