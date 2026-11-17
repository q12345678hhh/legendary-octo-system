package com.acme.platform;

import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.goal.GoalService;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * 目标接口：个人目标由本人维护，团队目标按组织单元维护关键结果。
 *
 * <p>归属人一律取当前会话对应的自然人，客户端不能指定他人；团队接口在授权时
 * 传入 orgUnitId，让组织数据范围参与判定。
 */
@RestController
@RequestMapping("/api/v1/goals")
class GoalController {

    private final AuthorizationGuard authorization;
    private final MemberStore memberStore;
    private final GoalService goalService;

    GoalController(AuthorizationGuard authorization, MemberStore memberStore,
                   GoalService goalService) {
        this.authorization = authorization;
        this.memberStore = memberStore;
        this.goalService = goalService;
    }

    record GoalRequest(String title, String description, String period, LocalDate dueDate,
                       Integer progress, String status, Long expectedVersion) {
    }

    record KeyResultCreateRequest(String title, BigDecimal targetValue, BigDecimal currentValue,
                                  String unit, Integer sortOrder) {
    }

    record KeyResultUpdateRequest(BigDecimal currentValue, Long expectedVersion) {
    }

    @GetMapping
    List<GoalService.GoalView> list() {
        authorization.require("work.goal.read", "work.goal", null, Map.of());
        return goalService.listOwn(personId());
    }

    @PostMapping
    GoalService.GoalView create(@RequestBody GoalRequest request) {
        authorization.require("work.goal.manage", "work.goal", null, Map.of());
        return goalService.create(personId(),
                new GoalService.GoalInput(request.title(), request.description(),
                        request.period(), request.dueDate(), request.progress(), request.status()),
                actorId());
    }

    @PutMapping("/{goalId}")
    GoalService.GoalView update(@PathVariable UUID goalId, @RequestBody GoalRequest request) {
        authorization.require("work.goal.manage", "work.goal", null, Map.of());
        return goalService.update(goalId, personId(),
                new GoalService.GoalInput(request.title(), request.description(),
                        request.period(), request.dueDate(), request.progress(), request.status()),
                request.expectedVersion());
    }

    @GetMapping("/team")
    List<GoalService.GoalView> listTeam(@RequestParam UUID orgUnitId) {
        requireTeam("work.goal.team.read", orgUnitId);
        return goalService.listTeam(orgUnitId);
    }

    @PostMapping("/team")
    GoalService.GoalView createTeam(@RequestParam UUID orgUnitId,
                                    @RequestBody GoalRequest request) {
        requireTeam("work.goal.team.manage", orgUnitId);
        return goalService.createTeam(orgUnitId, personId(),
                new GoalService.GoalInput(request.title(), request.description(),
                        request.period(), request.dueDate(), request.progress(), request.status()),
                actorId());
    }

    @PostMapping("/{goalId}/key-results")
    GoalService.KeyResultView addKeyResult(@PathVariable UUID goalId,
                                           @RequestParam UUID orgUnitId,
                                           @RequestBody KeyResultCreateRequest request) {
        requireTeam("work.goal.team.manage", orgUnitId);
        return goalService.addKeyResult(goalId, orgUnitId,
                new GoalService.KeyResultInput(request.title(), request.targetValue(),
                        request.currentValue(), request.unit(), request.sortOrder()));
    }

    @PutMapping("/{goalId}/key-results/{keyResultId}")
    GoalService.KeyResultView updateKeyResult(@PathVariable UUID goalId,
                                              @PathVariable UUID keyResultId,
                                              @RequestParam UUID orgUnitId,
                                              @RequestBody KeyResultUpdateRequest request) {
        requireTeam("work.goal.team.manage", orgUnitId);
        return goalService.updateKeyResult(goalId, keyResultId, orgUnitId,
                request.currentValue(), request.expectedVersion());
    }

    private void requireTeam(String permission, UUID orgUnitId) {
        authorization.require(permission, "org.unit", orgUnitId,
                Map.of("orgUnitId", orgUnitId));
    }

    private UUID personId() {
        ActorContext actor = authorization.actor();
        if (actor == null || actor.memberId() == null) {
            throw ApiException.forbidden("GOAL_ACTOR_MISSING", "当前会话没有成员身份");
        }
        return memberStore.findById(actor.memberId())
                .map(TenantMember::personId)
                .filter(java.util.Objects::nonNull)
                .orElseThrow(() -> ApiException.forbidden("GOAL_ACTOR_MISSING",
                        "当前成员未关联自然人"));
    }

    private String actorId() {
        ActorContext actor = authorization.actor();
        return actor == null || actor.actorId() == null ? "" : actor.actorId().toString();
    }
}
