package com.acme.platform.goal;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 目标：个人目标由本人查看与更新，团队目标按组织单元查看与维护关键结果。
 *
 * <p>归属人与组织单元一律从会话与授权范围解析，不接受客户端指定他人；
 * 团队目标进度由关键结果派生，不回写 progress 列。
 */
@Service
public class GoalService {

    private static final int TITLE_MAX = 120;
    private static final int DESCRIPTION_MAX = 2000;
    private static final int PERIOD_MAX = 20;
    private static final int UNIT_MAX = 20;

    private final GoalStore store;
    private final TenantTransactions transactions;

    public GoalService(GoalStore store, TenantTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    public record GoalView(UUID id, UUID ownerPersonId, String scopeType, UUID orgUnitId,
                           String title, String description, String period, LocalDate dueDate,
                           int progress, String status, long version,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt,
                           List<KeyResultView> keyResults) {
    }

    public record KeyResultView(UUID id, UUID goalId, String title, BigDecimal targetValue,
                                BigDecimal currentValue, String unit, int sortOrder,
                                long version) {
    }

    public record GoalInput(String title, String description, String period, LocalDate dueDate,
                            Integer progress, String status) {
    }

    public record KeyResultInput(String title, BigDecimal targetValue, BigDecimal currentValue,
                                 String unit, Integer sortOrder) {
    }

    public List<GoalView> listOwn(UUID ownerPersonId) {
        return transactions.inTenant(context -> store.listByOwner(ownerPersonId).stream()
                .map(this::view).toList());
    }

    public GoalView create(UUID ownerPersonId, GoalInput input, String actor) {
        String title = requireTitle(input.title());
        int progress = input.progress() == null ? 0 : progressValue(input.progress());
        return transactions.inTenant(context -> {
            UUID id = store.insert(ownerPersonId, title, text(input.description()),
                    period(input.period()), input.dueDate(), progress, actor);
            return view(require(id));
        });
    }

    public GoalView update(UUID id, UUID ownerPersonId, GoalInput input, Long expectedVersion) {
        String requestedTitle = input.title() == null ? null : requireTitle(input.title());
        Integer requestedProgress = input.progress() == null
                ? null : progressValue(input.progress());
        String status = status(input.status());
        return transactions.inTenant(context -> {
            GoalStore.GoalRow row = require(id);
            if (!row.ownerPersonId().equals(ownerPersonId) || !"person".equals(row.scopeType())) {
                throw ApiException.notFound("GOAL_NOT_FOUND", "目标不存在");
            }
            if (expectedVersion != null && expectedVersion != row.version()) {
                throw versionConflict();
            }
            String title = requestedTitle == null ? row.title() : requestedTitle;
            String description = input.description() == null
                    ? row.description() : text(input.description());
            String period = input.period() == null ? row.period() : period(input.period());
            int targetProgress = requestedProgress == null ? row.progress() : requestedProgress;
            String targetStatus = status == null ? row.status() : status;
            if (!store.update(id, title, description, period,
                    input.dueDate() == null ? row.dueDate() : input.dueDate(),
                    targetProgress, targetStatus, row.version())) {
                throw versionConflict();
            }
            return view(require(id));
        });
    }

    public List<GoalView> listTeam(UUID orgUnitId) {
        return transactions.inTenant(context -> store.listByUnit(orgUnitId).stream()
                .map(row -> view(row, keyResults(row.id())))
                .toList());
    }

    public GoalView createTeam(UUID orgUnitId, UUID ownerPersonId, GoalInput input, String actor) {
        String title = requireTitle(input.title());
        return transactions.inTenant(context -> {
            UUID id = store.insertTeam(ownerPersonId, orgUnitId, title,
                    text(input.description()), period(input.period()), input.dueDate(), actor);
            return view(require(id));
        });
    }

    public KeyResultView addKeyResult(UUID goalId, UUID orgUnitId, KeyResultInput input) {
        String title = requireKeyResultTitle(input.title());
        BigDecimal target = requireTarget(input.targetValue());
        BigDecimal current = input.currentValue() == null
                ? BigDecimal.ZERO : currentValue(input.currentValue());
        String unit = keyResultUnit(input.unit());
        return transactions.inTenant(context -> {
            GoalStore.GoalRow goal = requireTeamGoal(goalId, orgUnitId);
            int sortOrder = input.sortOrder() == null
                    ? store.countKeyResults(goal.id()) : sortOrder(input.sortOrder());
            UUID id = store.insertKeyResult(goal.id(), title, target, current, unit, sortOrder);
            return keyResultView(requireKeyResult(id));
        });
    }

    public KeyResultView updateKeyResult(UUID goalId, UUID keyResultId, UUID orgUnitId,
                                         BigDecimal requestedCurrent, Long expectedVersion) {
        BigDecimal current = currentValue(requestedCurrent);
        return transactions.inTenant(context -> {
            requireTeamGoal(goalId, orgUnitId);
            GoalStore.KeyResultRow row = requireKeyResult(keyResultId);
            if (!row.goalId().equals(goalId)) {
                throw ApiException.notFound("KEY_RESULT_NOT_FOUND", "关键结果不存在");
            }
            if (expectedVersion != null && expectedVersion != row.version()) {
                throw keyResultVersionConflict();
            }
            if (!store.updateKeyResultCurrent(keyResultId, current, row.version())) {
                throw keyResultVersionConflict();
            }
            return keyResultView(requireKeyResult(keyResultId));
        });
    }

    private GoalStore.GoalRow require(UUID id) {
        return store.find(id).orElseThrow(() ->
                ApiException.notFound("GOAL_NOT_FOUND", "目标不存在"));
    }

    private GoalStore.GoalRow requireTeamGoal(UUID goalId, UUID orgUnitId) {
        GoalStore.GoalRow row = require(goalId);
        if (!"team".equals(row.scopeType()) || !orgUnitId.equals(row.orgUnitId())) {
            throw ApiException.notFound("GOAL_NOT_FOUND", "团队目标不存在");
        }
        return row;
    }

    private GoalStore.KeyResultRow requireKeyResult(UUID id) {
        return store.findKeyResult(id).orElseThrow(() ->
                ApiException.notFound("KEY_RESULT_NOT_FOUND", "关键结果不存在"));
    }

    private List<KeyResultView> keyResults(UUID goalId) {
        return store.listKeyResults(goalId).stream().map(this::keyResultView).toList();
    }

    private KeyResultView keyResultView(GoalStore.KeyResultRow row) {
        return new KeyResultView(row.id(), row.goalId(), row.title(), row.targetValue(),
                row.currentValue(), row.unit(), row.sortOrder(), row.version());
    }

    private GoalView view(GoalStore.GoalRow row) {
        return view(row, List.of());
    }

    private GoalView view(GoalStore.GoalRow row, List<KeyResultView> keyResults) {
        return new GoalView(row.id(), row.ownerPersonId(), row.scopeType(), row.orgUnitId(),
                row.title(), row.description(), row.period(), row.dueDate(),
                progressOf(row, keyResults), row.status(), row.version(),
                row.createdAt(), row.updatedAt(), keyResults);
    }

    /** 团队目标有 KR 时按平均完成率派生进度；个人目标沿用列值。 */
    private static int progressOf(GoalStore.GoalRow row, List<KeyResultView> keyResults) {
        if (!"team".equals(row.scopeType()) || keyResults.isEmpty()) {
            return row.progress();
        }
        double total = 0;
        for (KeyResultView keyResult : keyResults) {
            double ratio = keyResult.currentValue().doubleValue()
                    / keyResult.targetValue().doubleValue();
            total += Math.min(1.0, Math.max(0.0, ratio));
        }
        return (int) Math.round(total / keyResults.size() * 100);
    }

    private static String requireTitle(String value) {
        String title = text(value);
        if (title.isEmpty() || title.length() > TITLE_MAX) {
            throw ApiException.badRequest("GOAL_TITLE_INVALID", "目标标题必填且不超过 120 字");
        }
        return title;
    }

    private static String requireKeyResultTitle(String value) {
        String title = text(value);
        if (title.isEmpty() || title.length() > TITLE_MAX) {
            throw ApiException.badRequest("KEY_RESULT_TITLE_INVALID",
                    "关键结果标题必填且不超过 120 字");
        }
        return title;
    }

    private static BigDecimal requireTarget(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("KEY_RESULT_TARGET_INVALID", "关键结果目标值必须大于 0");
        }
        return value;
    }

    private static BigDecimal currentValue(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("KEY_RESULT_CURRENT_INVALID", "关键结果当前值不能为负数");
        }
        return value;
    }

    private static String keyResultUnit(String value) {
        String unit = text(value);
        if (unit.length() > UNIT_MAX) {
            throw ApiException.badRequest("KEY_RESULT_UNIT_INVALID", "关键结果单位不超过 20 字");
        }
        return unit;
    }

    private static int sortOrder(int value) {
        if (value < 0 || value > 999) {
            throw ApiException.badRequest("KEY_RESULT_SORT_INVALID", "排序值必须在 0-999 之间");
        }
        return value;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String period(String value) {
        String period = text(value);
        if (period.length() > PERIOD_MAX) {
            throw ApiException.badRequest("GOAL_PERIOD_INVALID", "周期不超过 20 字");
        }
        return period;
    }

    private static int progressValue(int value) {
        if (value < 0 || value > 100) {
            throw ApiException.badRequest("GOAL_PROGRESS_INVALID", "进度必须在 0-100 之间");
        }
        return value;
    }

    private static String status(String value) {
        String status = text(value);
        if (status.isEmpty()) {
            return null;
        }
        if (!"active".equals(status) && !"achieved".equals(status)
                && !"archived".equals(status)) {
            throw ApiException.badRequest("GOAL_STATUS_INVALID",
                    "目标状态只能是 active、achieved 或 archived");
        }
        return status;
    }

    private static ApiException versionConflict() {
        return ApiException.conflict("GOAL_VERSION_CONFLICT", "目标已被其他人修改，请刷新后重试");
    }

    private static ApiException keyResultVersionConflict() {
        return ApiException.conflict("KEY_RESULT_VERSION_CONFLICT",
                "关键结果已被其他人修改，请刷新后重试");
    }
}
