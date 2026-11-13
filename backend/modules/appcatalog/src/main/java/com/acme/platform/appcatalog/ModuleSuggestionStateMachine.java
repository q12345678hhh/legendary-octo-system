package com.acme.platform.appcatalog;

import com.acme.platform.platform.ApiException;
import java.util.Map;
import java.util.Set;

/**
 * 模块建议状态机。
 *
 * <p>只定义「允许的动作」；角色校验在控制器完成，必填字段与发起人校验在服务层完成。
 * 非法动作统一返回 409，避免前端或集成方凭猜测跳状态。
 */
public final class ModuleSuggestionStateMachine {

    public static final Set<String> STATUSES = Set.of(
            "draft", "communicating", "submitted", "configuring", "testing",
            "rectifying", "test_failed", "pending_final_approval", "rejected",
            "withdrawn", "enabled", "special_pending_review", "paused", "closed",
            "deprecated");

    /** 终态：只能停留在关闭或弃用。 */
    private static final Set<String> TERMINAL = Set.of("closed", "deprecated");

    public record Transition(String targetStatus, boolean requiresReviewer,
                             boolean requiresComment, boolean requiresConfigOwner,
                             boolean requesterOnly) {
    }

    private static final Map<String, Transition> TRANSITIONS = Map.ofEntries(
            Map.entry("submit", new Transition("communicating", false, false, false, true)),
            Map.entry("submit-review", new Transition("submitted", false, false, false, true)),
            Map.entry("accept", new Transition("configuring", true, false, true, false)),
            Map.entry("reject", new Transition("rejected", true, true, false, false)),
            Map.entry("request-more", new Transition("communicating", true, true, false, false)),
            Map.entry("withdraw", new Transition("withdrawn", false, false, false, true)),
            Map.entry("start-test", new Transition("testing", true, false, false, false)),
            Map.entry("test-pass", new Transition("pending_final_approval", true, false, false,
                    false)),
            Map.entry("test-fail", new Transition("test_failed", true, true, false, false)),
            Map.entry("rectify", new Transition("rectifying", true, false, false, false)),
            Map.entry("approve", new Transition("enabled", true, false, false, false)),
            Map.entry("special-review", new Transition("special_pending_review", true, false, false,
                    false)),
            Map.entry("review-pass", new Transition("enabled", true, false, false, false)),
            Map.entry("pause", new Transition("paused", true, false, false, false)),
            Map.entry("resume", new Transition("enabled", true, false, false, false)),
            Map.entry("deprecate", new Transition("deprecated", true, false, false, false)),
            Map.entry("close", new Transition("closed", true, false, false, false)));

    private ModuleSuggestionStateMachine() {
    }

    public static Transition transition(String status, String action) {
        if (status == null || !STATUSES.contains(status)) {
            throw invalid(status, action);
        }
        if (TERMINAL.contains(status)) {
            throw invalid(status, action);
        }
        Transition transition = TRANSITIONS.get(action);
        if (transition == null || !allowedFrom(status, action)) {
            throw invalid(status, action);
        }
        return transition;
    }

    /** 沟通意见不改变状态，只追加一条事件。 */
    public static boolean isComment(String action) {
        return "comment".equals(action);
    }

    /** 只有发起人可执行的动作：提交与撤回。 */
    public static Set<String> requesterActions() {
        return Set.of("submit", "submit-review", "withdraw");
    }

    private static boolean allowedFrom(String status, String action) {
        return switch (action) {
            case "submit" -> "draft".equals(status);
            case "submit-review" -> "communicating".equals(status);
            case "accept" -> "submitted".equals(status);
            case "reject", "withdraw" ->
                    "draft".equals(status) || "communicating".equals(status)
                            || "submitted".equals(status);
            case "request-more" -> "submitted".equals(status);
            case "start-test" -> "configuring".equals(status) || "rectifying".equals(status);
            case "test-pass", "test-fail" -> "testing".equals(status);
            case "rectify" -> "test_failed".equals(status);
            case "approve" -> "pending_final_approval".equals(status);
            case "special-review" -> "enabled".equals(status);
            case "review-pass" -> "special_pending_review".equals(status);
            case "pause" -> "enabled".equals(status);
            case "resume" -> "paused".equals(status);
            case "deprecate" -> "enabled".equals(status) || "paused".equals(status);
            case "close" -> true;
            default -> false;
        };
    }

    private static ApiException invalid(String status, String action) {
        return ApiException.conflict("APP_SUGGESTION_TRANSITION_INVALID",
                "建议当前状态 %s 不允许执行动作 %s".formatted(status, action));
    }
}
