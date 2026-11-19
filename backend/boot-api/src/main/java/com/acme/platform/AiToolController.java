package com.acme.platform;

import com.acme.platform.ai.AiToolService;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 工具目录接口（插件平台最小版）。
 *
 * <p>只登记工具元数据与启停/评分，不执行调用；调用凭据后续由凭证模块托管。
 */
@RestController
@RequestMapping("/api/v1/ai/tools")
class AiToolController {

    private final AuthorizationGuard authorization;
    private final AiToolService toolService;

    AiToolController(AuthorizationGuard authorization, AiToolService toolService) {
        this.authorization = authorization;
        this.toolService = toolService;
    }

    record RegisterToolRequest(String code, String name, String description, String endpointUrl,
                               String inputSchema) {
    }

    record StatusRequest(String status, Long expectedVersion) {
    }

    record RatingRequest(Integer rating, Long expectedVersion) {
    }

    @GetMapping
    List<AiToolService.ToolView> list() {
        require("ai.context.read");
        return toolService.list();
    }

    @PostMapping
    AiToolService.ToolView register(@RequestBody RegisterToolRequest request) {
        require("ai.tool.register");
        return toolService.register(new AiToolService.RegisterInput(request.code(),
                request.name(), request.description(), request.endpointUrl(),
                request.inputSchema()), actorId());
    }

    @PutMapping("/{toolId}/status")
    AiToolService.ToolView updateStatus(@PathVariable UUID toolId,
                                        @RequestBody StatusRequest request) {
        AiToolService.ToolView tool = toolService.get(toolId);
        // 启用必须发生在他人评分之后：授权判定依赖 ratedBy 属性，先把前置条件讲清楚。
        if ("enabled".equals(request.status()) && tool.rating() == null) {
            throw ApiException.unprocessable("AI_TOOL_RATING_REQUIRED",
                    "启用前请先由他人完成评分");
        }
        Map<String, Object> attributes = new java.util.HashMap<>();
        attributes.put("registeredBy", tool.createdBy());
        attributes.put("ratedBy", tool.ratedBy());
        // 工具是租户级资源，没有部门/负责人数据范围；职责分离由 attribute 规则承担。
        require("ai.tool.enable", "ai.tool", null, attributes);
        return toolService.setStatus(toolId, request.status(), request.expectedVersion());
    }

    @PutMapping("/{toolId}/rating")
    AiToolService.ToolView updateRating(@PathVariable UUID toolId,
                                        @RequestBody RatingRequest request) {
        AiToolService.ToolView tool = toolService.get(toolId);
        require("ai.tool.rate", "ai.tool", null,
                Map.of("registeredBy", tool.createdBy() == null ? "" : tool.createdBy()));
        return toolService.rate(toolId, request.rating(), request.expectedVersion(), actorId());
    }

    private void require(String permission) {
        require(permission, "ai.tool", null, Map.of());
    }

    private void require(String permission, String resourceType, UUID resourceId,
                         Map<String, Object> attributes) {
        authorization.require(permission, resourceType, resourceId, attributes);
    }

    private String actorId() {
        ActorContext actor = authorization.actor();
        return actor == null || actor.actorId() == null ? "" : actor.actorId().toString();
    }
}
