package com.acme.platform;

import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.tenancy.privacy.PrivacySettingsService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 隐私中心接口。
 *
 * <p>分类说明与设置只对租户管理员开放；我的数据只返回当前登录主体本人的记录，
 * personId 永远从会话解析，请求体不能指定他人。
 */
@RestController
@RequestMapping("/api/v1/privacy")
class PrivacyController {

    private final PrivacyService privacy;
    private final PrivacySettingsService settings;
    private final AuthorizationGuard authorization;
    private final MemberStore memberStore;

    PrivacyController(PrivacyService privacy, PrivacySettingsService settings,
                      AuthorizationGuard authorization, MemberStore memberStore) {
        this.privacy = privacy;
        this.settings = settings;
        this.authorization = authorization;
        this.memberStore = memberStore;
    }

    record SettingsRequest(String dataResidency, boolean trainingOptOut,
                           int voiceTranscriptRetentionDays, int expenseOcrRetentionDays) {
    }

    record DeleteRequest(List<UUID> voiceCaptureIds, List<UUID> expenseDocumentIds) {
    }

    @GetMapping("/data-classes")
    List<PrivacyService.DataClassView> dataClasses() {
        requireAdmin();
        return privacy.dataClasses();
    }

    @GetMapping("/settings")
    PrivacyService.SettingsView settings() {
        requireAdmin();
        return privacy.settings();
    }

    @PutMapping("/settings")
    PrivacyService.SettingsView updateSettings(@RequestBody SettingsRequest request) {
        requireAdmin();
        return privacy.updateSettings(new PrivacySettingsService.Input(
                request.dataResidency(), request.trainingOptOut(),
                request.voiceTranscriptRetentionDays(),
                request.expenseOcrRetentionDays()));
    }

    @GetMapping("/my-data")
    PrivacyService.MyDataView myData() {
        return privacy.myData(requirePerson());
    }

    @GetMapping("/my-data/export")
    PrivacyService.ExportView export() {
        return privacy.export(requirePerson());
    }

    @PostMapping("/my-data/delete")
    PrivacyService.DeleteResult delete(@RequestBody DeleteRequest request) {
        return privacy.delete(requirePerson(), request.voiceCaptureIds(),
                request.expenseDocumentIds());
    }

    private void requireAdmin() {
        authorization.require("tenant.admin", "tenant", null, Map.of());
    }

    private UUID requirePerson() {
        ActorContext actor = authorization.actor();
        if (actor == null || actor.memberId() == null) {
            throw ApiException.forbidden("PERSON_NOT_BOUND", "当前账号未绑定员工");
        }
        return memberStore.findById(actor.memberId())
                .map(TenantMember::personId)
                .filter(java.util.Objects::nonNull)
                .orElseThrow(() -> ApiException.forbidden("PERSON_NOT_BOUND",
                        "当前账号未绑定员工"));
    }
}
