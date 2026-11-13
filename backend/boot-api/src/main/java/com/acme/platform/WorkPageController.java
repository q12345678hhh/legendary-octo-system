package com.acme.platform;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.worklog.access.WorkAccessLogService;
import com.acme.platform.worklog.access.WorkAccessLogStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人工作页与访问留痕。
 *
 * <p>关系权限完全交给统一授权链：本人、直接下属、汇报链、限时代管、老板已提交域。
 * 项目成员只能走协作模块的项目投影，这里不会因为同项目而放行。
 */
@RestController
@RequestMapping("/api/v1")
class WorkPageController {

    private static final int MAX_ACCESS_LOG = 100;

    private final AuthorizationGuard authorization;
    private final MemberStore memberStore;
    private final WorkPagePersonStore personStore;
    private final List<WorkPageSectionProvider> sectionProviders;
    private final WorkAccessLogService accessLog;

    WorkPageController(AuthorizationGuard authorization,
                       MemberStore memberStore,
                       WorkPagePersonStore personStore,
                       List<WorkPageSectionProvider> sectionProviders,
                       WorkAccessLogService accessLog) {
        this.authorization = authorization;
        this.memberStore = memberStore;
        this.personStore = personStore;
        this.sectionProviders = sectionProviders;
        this.accessLog = accessLog;
    }

    record Section(String key, boolean available, Object data) {
    }

    record WorkPageView(UUID personId, String displayName, String status, boolean self,
                        List<Section> sections) {
    }

    record AccessLogRow(UUID id, UUID viewerPersonId, String resourceType, String resourceId,
                        String action, List<String> factors, String occurredAt) {
    }

    @GetMapping("/me/work-page")
    WorkPageView myWorkPage() {
        UUID personId = viewerPersonId().orElseThrow(() ->
                ApiException.forbidden("PERSON_NOT_BOUND", "当前账号未绑定员工"));
        return build(personId, true);
    }

    @GetMapping("/people/{personId}/work-page")
    WorkPageView personWorkPage(@PathVariable UUID personId) {
        AccessDecision decision = authorization.require("business.work.read", "business.work",
                personId, Map.of("personId", personId, "ownerId", personId));
        boolean self = viewerPersonId().map(personId::equals).orElse(false);
        WorkPageView view = build(personId, self);
        accessLog.record(viewerPersonId().orElse(null), personId, "work_page",
                personId.toString(), "view", decision.factors());
        return view;
    }

    @GetMapping("/me/work-access-log")
    List<AccessLogRow> myAccessLog(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        UUID personId = viewerPersonId().orElseThrow(() ->
                ApiException.forbidden("PERSON_NOT_BOUND", "当前账号未绑定员工"));
        return accessLog.recent(personId, Math.min(limit, MAX_ACCESS_LOG)).stream()
                .map(WorkPageController::toRow)
                .toList();
    }

    @GetMapping("/people/{personId}/work-access-log")
    List<AccessLogRow> personAccessLog(@PathVariable UUID personId,
                                       @RequestParam(name = "limit", defaultValue = "50") int limit) {
        UUID viewer = viewerPersonId().orElse(null);
        if (viewer == null || !viewer.equals(personId)) {
            throw ApiException.forbidden("ACCESS_LOG_SELF_ONLY", "只能查看自己的访问记录");
        }
        return accessLog.recent(personId, Math.min(limit, MAX_ACCESS_LOG)).stream()
                .map(WorkPageController::toRow)
                .toList();
    }

    private WorkPageView build(UUID personId, boolean self) {
        WorkPagePersonStore.PersonHeader person = personStore.find(personId)
                .orElseThrow(() -> ApiException.notFound("PERSON_NOT_FOUND",
                        "自然人不存在：" + personId));
        List<Section> sections = new ArrayList<>();
        for (WorkPageSectionProvider provider : sectionProviders) {
            Object data = provider.load(personId, self);
            if (data != null) {
                sections.add(new Section(provider.key(), true, data));
            }
        }
        return new WorkPageView(personId, person.displayName(), person.status(), self, sections);
    }

    private static AccessLogRow toRow(WorkAccessLogStore.AccessEntry entry) {
        return new AccessLogRow(entry.id(), entry.viewerPersonId(), entry.resourceType(),
                entry.resourceId(), entry.action(), entry.factors(),
                entry.occurredAt() == null ? null : entry.occurredAt().toString());
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
