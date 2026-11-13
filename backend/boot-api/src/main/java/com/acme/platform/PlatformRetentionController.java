package com.acme.platform;

import com.acme.platform.identity.operator.PlatformPermissionCatalog;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.retention.DeletionRequest;
import com.acme.platform.platform.retention.DeletionRequestService;
import com.acme.platform.platform.settings.PlatformSettingService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 平台侧保留策略与数据主体请求。
 *
 * <p>隐私与安全运营同属一个台账，复用 {@code platform.security.*} 权限；
 * 判定与执行要求 {@code *.manage}（叠加 MFA）。
 */
@RestController
@RequestMapping("/platform/api/v1")
class PlatformRetentionController {

    record DecisionRequest(String decisionCode, String note) {
    }

    record RequestView(
            UUID id,
            UUID tenantId,
            String subjectType,
            UUID subjectId,
            String subjectHash,
            String status,
            String decisionCode,
            String decisionNote,
            OffsetDateTime requestedAt,
            OffsetDateTime decidedAt,
            OffsetDateTime executedAt) {

        static RequestView from(DeletionRequest request) {
            return new RequestView(request.id(), request.tenantId(), request.subjectType(),
                    request.subjectId(), request.subjectHash(), request.status(), request.decisionCode(),
                    request.decisionNote(), request.requestedAt(), request.decidedAt(), request.executedAt());
        }
    }

    private final PlatformSettingService settings;
    private final DeletionRequestService requests;
    private final DataSubjectRequestOrchestrator orchestrator;
    private final TombstoneReplayService replay;

    PlatformRetentionController(PlatformSettingService settings,
                                DeletionRequestService requests,
                                DataSubjectRequestOrchestrator orchestrator,
                                TombstoneReplayService replay) {
        this.settings = settings;
        this.requests = requests;
        this.orchestrator = orchestrator;
        this.replay = replay;
    }

    @GetMapping("/retention/policy")
    List<PlatformSettingService.SettingView> policy() {
        PlatformActors.require(PlatformPermissionCatalog.SETTINGS_READ);
        return settings.all().stream()
                .filter(view -> view.key().startsWith("retention."))
                .toList();
    }

    @PutMapping("/retention/policy")
    PlatformSettingService.SettingView updatePolicy(@RequestBody JsonNode body) {
        JsonNode keyNode = body == null ? null : body.get("key");
        if (keyNode == null || !keyNode.isString() || !keyNode.asString().startsWith("retention.")) {
            throw ApiException.badRequest("RETENTION_POLICY_KEY_INVALID",
                    "只允许修改 retention.* 开头的保留策略键");
        }
        JsonNode value = body.get("value");
        return settings.put(keyNode.asString(), value, PlatformActors.ref(
                PlatformActors.requireManage(PlatformPermissionCatalog.SETTINGS_MANAGE)));
    }

    @GetMapping("/privacy/requests")
    List<RequestView> list(@RequestParam(required = false) String status,
                           @RequestParam(defaultValue = "50") int limit) {
        PlatformActors.require(PlatformPermissionCatalog.SECURITY_READ);
        return requests.listByStatus(status, limit).stream().map(RequestView::from).toList();
    }

    @PostMapping("/privacy/requests/{id}/approve")
    RequestView approve(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest body) {
        var actor = PlatformActors.requireManage(PlatformPermissionCatalog.SECURITY_MANAGE);
        String code = body == null ? "APPROVED" : body.decisionCode();
        String note = body == null ? null : body.note();
        return RequestView.from(requests.approve(id, code, note, actor.email()));
    }

    @PostMapping("/privacy/requests/{id}/reject")
    RequestView reject(@PathVariable UUID id, @RequestBody DecisionRequest body) {
        var actor = PlatformActors.requireManage(PlatformPermissionCatalog.SECURITY_MANAGE);
        return RequestView.from(requests.reject(id, body.decisionCode(), body.note(), actor.email()));
    }

    @PostMapping("/privacy/requests/{id}/execute")
    RequestView execute(@PathVariable UUID id) {
        var actor = PlatformActors.requireManage(PlatformPermissionCatalog.SECURITY_MANAGE);
        return RequestView.from(orchestrator.execute(id, PlatformActors.ref(actor)));
    }

    @PostMapping("/retention/tombstones/replay")
    TombstoneReplayService.ReplayResult replay(@RequestParam(defaultValue = "1000") int limit) {
        var actor = PlatformActors.requireManage(PlatformPermissionCatalog.SECURITY_MANAGE);
        return replay.replay(limit, PlatformActors.ref(actor));
    }
}
