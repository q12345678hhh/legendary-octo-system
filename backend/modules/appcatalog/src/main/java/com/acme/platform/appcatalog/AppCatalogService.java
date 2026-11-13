package com.acme.platform.appcatalog;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 模块目录与建议的应用服务。
 *
 * <p>目录只做登记与检索；建议走固定状态机，非法流转、缺少沟通意见或配置负责人都被拒绝。
 * 每次创建与流转都会追加事件，事件同时充当审计与站内通知。
 */
@Service
public class AppCatalogService {

    private final AppCatalogStore store;
    private final TenantTransactions transactions;
    private final ObjectMapper objectMapper;

    public AppCatalogService(AppCatalogStore store, TenantTransactions transactions,
                             ObjectMapper objectMapper) {
        this.store = store;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public record ModuleInput(String code, String name, String category, String description,
                              String templateKey, UUID ownerPersonId, String version,
                              String dependencies, String applicableScope, String healthStatus,
                              String installStatus) {
    }

    public record SuggestionInput(String title, String businessProblem, String goal, String scope,
                                  String dataScope, String permissionsNeeded, String costEstimate,
                                  String benefitEstimate, UUID suggestedOwnerPersonId) {
    }

    public record ActionInput(String action, String comment, UUID configOwnerPersonId) {
    }

    // ------------------------------------------------------------------ 目录

    public List<AppCatalogViews.ModuleView> listModules(String keyword, String category,
                                                       String health) {
        return transactions.inTenant(context -> store.modules(keyword, category, health).stream()
                .map(AppCatalogViews::module).toList());
    }

    public AppCatalogViews.ModuleView getModule(UUID id) {
        return transactions.inTenant(context -> store.module(id)
                .map(AppCatalogViews::module)
                .orElseThrow(() -> ApiException.notFound("APP_MODULE_NOT_FOUND", "模块不存在")));
    }

    public AppCatalogViews.ModuleView createModule(ModuleInput input) {
        validateModule(input);
        String dependencies = normalizeDependencies(input.dependencies());
        return transactions.inTenant(context -> {
            if (store.moduleByCode(input.code()).isPresent()) {
                throw ApiException.conflict("APP_MODULE_CODE_TAKEN", "模块编码已存在");
            }
            UUID id = store.insertModule(input.code().trim(), input.name().trim(),
                    defaulted(input.category(), "通用"), defaulted(input.description(), ""),
                    blankToNull(input.templateKey()), input.ownerPersonId(),
                    defaulted(input.version(), "1.0.0"), dependencies,
                    defaulted(input.applicableScope(), "tenant"),
                    defaulted(input.healthStatus(), "unknown"),
                    defaulted(input.installStatus(), "not_installed"));
            return store.module(id).map(AppCatalogViews::module).orElseThrow();
        });
    }

    public AppCatalogViews.ModuleView updateModule(UUID id, ModuleInput input) {
        validateModule(input);
        String dependencies = normalizeDependencies(input.dependencies());
        return transactions.inTenant(context -> {
            store.module(id).orElseThrow(() ->
                    ApiException.notFound("APP_MODULE_NOT_FOUND", "模块不存在"));
            store.updateModule(id, input.name().trim(), defaulted(input.category(), "通用"),
                    defaulted(input.description(), ""), blankToNull(input.templateKey()),
                    input.ownerPersonId(), defaulted(input.version(), "1.0.0"), dependencies,
                    defaulted(input.applicableScope(), "tenant"),
                    defaulted(input.healthStatus(), "unknown"),
                    defaulted(input.installStatus(), "not_installed"));
            return store.module(id).map(AppCatalogViews::module).orElseThrow();
        });
    }

    // ------------------------------------------------------------------ 建议

    public List<AppCatalogViews.SuggestionView> listSuggestions(UUID requesterPersonId,
                                                                boolean all) {
        return transactions.inTenant(context -> {
            List<AppCatalogStore.SuggestionRow> rows = all
                    ? store.allSuggestions()
                    : store.suggestionsForPerson(requesterPersonId);
            return rows.stream().map(AppCatalogViews::suggestion).toList();
        });
    }

    public AppCatalogViews.SuggestionDetail getSuggestion(UUID id) {
        return transactions.inTenant(context -> detail(id));
    }

    public AppCatalogViews.SuggestionView createSuggestion(SuggestionInput input,
                                                           UUID requesterPersonId) {
        if (input.title() == null || input.title().isBlank()) {
            throw ApiException.badRequest("APP_SUGGESTION_TITLE_REQUIRED", "建议标题不能为空");
        }
        if (input.businessProblem() == null || input.businessProblem().isBlank()) {
            throw ApiException.badRequest("APP_SUGGESTION_PROBLEM_REQUIRED", "业务问题不能为空");
        }
        return transactions.inTenant(context -> {
            UUID id = store.insertSuggestion(input.title().trim(), requesterPersonId,
                    input.businessProblem().trim(), defaulted(input.goal(), ""),
                    defaulted(input.scope(), ""), defaulted(input.dataScope(), ""),
                    defaulted(input.permissionsNeeded(), ""),
                    defaulted(input.costEstimate(), ""), defaulted(input.benefitEstimate(), ""),
                    input.suggestedOwnerPersonId());
            store.insertEvent(id, "transition", null, "draft", "create", requesterPersonId,
                    requesterPersonId, "发起模块建议");
            return detail(id).suggestion();
        });
    }

    public AppCatalogViews.SuggestionDetail act(UUID id, ActionInput input, UUID actorPersonId) {
        if (input.action() == null || input.action().isBlank()) {
            throw ApiException.badRequest("APP_SUGGESTION_ACTION_REQUIRED", "动作不能为空");
        }
        return transactions.inTenant(context -> {
            AppCatalogStore.SuggestionRow row = store.suggestion(id).orElseThrow(() ->
                    ApiException.notFound("APP_SUGGESTION_NOT_FOUND", "模块建议不存在"));
            String comment = input.comment() == null ? "" : input.comment().trim();

            if (ModuleSuggestionStateMachine.isComment(input.action())) {
                if (comment.isEmpty()) {
                    throw ApiException.badRequest("APP_SUGGESTION_COMMENT_REQUIRED",
                            "沟通意见不能为空");
                }
                store.insertEvent(id, "comment", row.status(), row.status(), "comment",
                        actorPersonId, row.requesterPersonId(), comment);
                return detail(id);
            }

            ModuleSuggestionStateMachine.Transition transition =
                    ModuleSuggestionStateMachine.transition(row.status(), input.action());
            if (transition.requesterOnly()
                    && !row.requesterPersonId().equals(actorPersonId)) {
                throw ApiException.forbidden("APP_SUGGESTION_NOT_REQUESTER",
                        "只有发起人可以提交或撤回建议");
            }
            if (transition.requiresComment() && comment.isEmpty()) {
                throw ApiException.badRequest("APP_SUGGESTION_COMMENT_REQUIRED",
                        "该动作必须填写意见");
            }
            if (transition.requiresConfigOwner() && input.configOwnerPersonId() == null) {
                throw ApiException.badRequest("APP_SUGGESTION_CONFIG_OWNER_REQUIRED",
                        "受理时必须指定配置负责人");
            }

            store.updateStatus(id, transition.targetStatus());
            if (transition.requiresConfigOwner()) {
                store.updateConfigOwner(id, input.configOwnerPersonId());
            }
            store.insertEvent(id, "transition", row.status(), transition.targetStatus(),
                    input.action(), actorPersonId, row.requesterPersonId(), comment);
            if (transition.requiresConfigOwner()) {
                store.insertEvent(id, "notification", row.status(), transition.targetStatus(),
                        "config-owner-assigned", actorPersonId, input.configOwnerPersonId(),
                        "你被指定为该模块建议的配置负责人");
            }
            return detail(id);
        });
    }

    private AppCatalogViews.SuggestionDetail detail(UUID id) {
        AppCatalogStore.SuggestionRow row = store.suggestion(id).orElseThrow(() ->
                ApiException.notFound("APP_SUGGESTION_NOT_FOUND", "模块建议不存在"));
        return new AppCatalogViews.SuggestionDetail(AppCatalogViews.suggestion(row),
                store.events(id).stream().map(AppCatalogViews::event).toList());
    }

    // ------------------------------------------------------------------ 校验

    private void validateModule(ModuleInput input) {
        if (input.code() == null || !input.code().trim().matches("^[a-z][a-z0-9._-]{2,63}$")) {
            throw ApiException.badRequest("APP_MODULE_CODE_INVALID",
                    "模块编码必须以小写字母开头，只含小写字母、数字、点、下划线与连字符");
        }
        if (input.name() == null || input.name().isBlank()) {
            throw ApiException.badRequest("APP_MODULE_NAME_REQUIRED", "模块名称不能为空");
        }
        requireEnum(input.applicableScope(), "APP_MODULE_SCOPE_INVALID",
                java.util.Set.of("tenant", "department", "project", "person"));
        requireEnum(input.healthStatus(), "APP_MODULE_HEALTH_INVALID",
                java.util.Set.of("healthy", "degraded", "offline", "unknown"));
        requireEnum(input.installStatus(), "APP_MODULE_INSTALL_INVALID",
                java.util.Set.of("not_installed", "installed", "disabled"));
    }

    private void requireEnum(String value, String code, java.util.Set<String> allowed) {
        if (value != null && !value.isBlank() && !allowed.contains(value)) {
            throw ApiException.badRequest(code, "取值必须是 " + String.join("/", allowed));
        }
    }

    private String normalizeDependencies(String dependencies) {
        String value = dependencies == null || dependencies.isBlank() ? "[]" : dependencies;
        JsonNode node = objectMapper.readTree(value);
        if (!node.isArray()) {
            throw ApiException.badRequest("APP_MODULE_DEPENDENCIES_INVALID", "依赖必须是数组");
        }
        return node.toString();
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
