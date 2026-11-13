package com.acme.platform.appcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.platform.ApiException;
import org.junit.jupiter.api.Test;

/** 模块建议状态机：合法链路、非法跳转与必填约束。 */
class ModuleSuggestionStateMachineTest {

    @Test
    void fullHappyPathReachesEnabled() {
        String status = "draft";
        status = apply(status, "submit");
        status = apply(status, "submit-review");
        status = apply(status, "accept");
        status = apply(status, "start-test");
        status = apply(status, "test-pass");
        status = apply(status, "approve");

        assertThat(status).isEqualTo("enabled");
    }

    @Test
    void testFailureCanBeRectifiedAndRetested() {
        String status = "testing";
        status = apply(status, "test-fail");
        assertThat(status).isEqualTo("test_failed");
        status = apply(status, "rectify");
        assertThat(status).isEqualTo("rectifying");
        status = apply(status, "start-test");
        assertThat(status).isEqualTo("testing");
    }

    @Test
    void illegalJumpIsRejectedWithConflict() {
        assertThatThrownBy(() -> ModuleSuggestionStateMachine.transition("enabled", "submit"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("APP_SUGGESTION_TRANSITION_INVALID");
                });
    }

    @Test
    void closedAndDeprecatedAreTerminal() {
        assertThatThrownBy(() -> ModuleSuggestionStateMachine.transition("closed", "close"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ModuleSuggestionStateMachine.transition("deprecated", "approve"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void unknownActionIsRejected() {
        assertThatThrownBy(() -> ModuleSuggestionStateMachine.transition("draft", "approve"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void acceptRequiresConfigOwnerAndRejectRequiresComment() {
        assertThat(ModuleSuggestionStateMachine.transition("submitted", "accept")
                .requiresConfigOwner()).isTrue();
        assertThat(ModuleSuggestionStateMachine.transition("submitted", "reject")
                .requiresComment()).isTrue();
        assertThat(ModuleSuggestionStateMachine.transition("draft", "submit")
                .requesterOnly()).isTrue();
    }

    private static String apply(String status, String action) {
        return ModuleSuggestionStateMachine.transition(status, action).targetStatus();
    }
}
