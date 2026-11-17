package com.acme.platform.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 目标：个人归属校验，团队目标与关键结果派生进度、范围与版本冲突。 */
class GoalServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID GOAL = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID KEY_RESULT = UUID.randomUUID();

    private GoalStore store;
    private GoalService service;

    @BeforeEach
    void setUp() {
        store = mock(GoalStore.class);
        TenantTransactions transactions = mock(TenantTransactions.class);
        when(transactions.inTenant(any(Function.class))).thenAnswer(invocation -> {
            Function<RequestContext, Object> work = invocation.getArgument(0);
            return work.apply(new RequestContext(TENANT, "actor", "corr", "trace"));
        });
        service = new GoalService(store, transactions);
    }

    @Test
    void createRejectsBlankTitle() {
        assertThatThrownBy(() -> service.create(PERSON,
                new GoalService.GoalInput("  ", "", "", null, null, null), "actor"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("GOAL_TITLE_INVALID"));
        verify(store, never()).insert(any(), anyString(), anyString(), anyString(), any(),
                anyInt(), anyString());
    }

    @Test
    void createTrimsFieldsAndDefaultsProgress() {
        when(store.insert(PERSON, "季度目标", "说明", "2026-Q4", null, 0, "actor"))
                .thenReturn(GOAL);
        when(store.find(GOAL)).thenReturn(Optional.of(goal(PERSON, 1L)));

        GoalService.GoalView view = service.create(PERSON,
                new GoalService.GoalInput(" 季度目标 ", " 说明 ", " 2026-Q4 ", null, null, null),
                "actor");

        verify(store).insert(PERSON, "季度目标", "说明", "2026-Q4", null, 0, "actor");
        assertThat(view.progress()).isZero();
    }

    @Test
    void updateRejectsGoalOwnedBySomeoneElse() {
        when(store.find(GOAL)).thenReturn(Optional.of(goal(OTHER, 1L)));

        assertThatThrownBy(() -> service.update(GOAL, PERSON,
                new GoalService.GoalInput("新标题", "", "", null, null, null), 1L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(404);
                    assertThat(error.code()).isEqualTo("GOAL_NOT_FOUND");
                });
    }

    @Test
    void updateRejectsTeamGoal() {
        when(store.find(GOAL)).thenReturn(Optional.of(teamGoal(UNIT, 1L, 0)));

        assertThatThrownBy(() -> service.update(GOAL, PERSON,
                new GoalService.GoalInput("新标题", "", "", null, null, null), 1L))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("GOAL_NOT_FOUND"));
    }

    @Test
    void progressMustBeBetweenZeroAndHundred() {
        assertThatThrownBy(() -> service.update(GOAL, PERSON,
                new GoalService.GoalInput("标题", "", "", null, 101, null), 1L))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("GOAL_PROGRESS_INVALID"));
    }

    @Test
    void staleVersionIsRejected() {
        when(store.find(GOAL)).thenReturn(Optional.of(goal(PERSON, 3L)));

        assertThatThrownBy(() -> service.update(GOAL, PERSON,
                new GoalService.GoalInput("标题", "", "", null, 50, null), 2L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("GOAL_VERSION_CONFLICT");
                });
        verify(store, never()).update(any(), anyString(), anyString(), anyString(), any(),
                anyInt(), anyString(), anyLong());
    }

    @Test
    void teamProgressIsDerivedFromKeyResults() {
        when(store.listByUnit(UNIT)).thenReturn(List.of(teamGoal(UNIT, 1L, 0)));
        when(store.listKeyResults(GOAL)).thenReturn(List.of(
                keyResult(BigDecimal.valueOf(100), BigDecimal.valueOf(50), 0),
                keyResult(BigDecimal.TEN, BigDecimal.TEN, 1),
                keyResult(BigDecimal.TEN, BigDecimal.valueOf(20), 2)));

        List<GoalService.GoalView> goals = service.listTeam(UNIT);

        assertThat(goals).hasSize(1);
        assertThat(goals.get(0).keyResults()).hasSize(3);
        // (50/100 + 10/10 + min(20/10, 1)) / 3 = 83%，超额完成按 100% 封顶
        assertThat(goals.get(0).progress()).isEqualTo(83);
    }

    @Test
    void teamGoalWithoutKeyResultsKeepsStoredProgress() {
        when(store.listByUnit(UNIT)).thenReturn(List.of(teamGoal(UNIT, 1L, 40)));
        when(store.listKeyResults(GOAL)).thenReturn(List.of());

        assertThat(service.listTeam(UNIT).get(0).progress()).isEqualTo(40);
    }

    @Test
    void addKeyResultRejectsPersonalGoal() {
        when(store.find(GOAL)).thenReturn(Optional.of(goal(PERSON, 1L)));

        assertThatThrownBy(() -> service.addKeyResult(GOAL, UNIT,
                new GoalService.KeyResultInput("签约客户", BigDecimal.TEN, null, "家", null)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(404);
                    assertThat(error.code()).isEqualTo("GOAL_NOT_FOUND");
                });
    }

    @Test
    void keyResultTargetMustBePositive() {
        assertThatThrownBy(() -> service.addKeyResult(GOAL, UNIT,
                new GoalService.KeyResultInput("签约客户", BigDecimal.ZERO, null, "家", null)))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("KEY_RESULT_TARGET_INVALID"));
        verify(store, never()).insertKeyResult(any(), anyString(), any(), any(), anyString(),
                anyInt());
    }

    @Test
    void keyResultStaleVersionIsRejected() {
        when(store.find(GOAL)).thenReturn(Optional.of(teamGoal(UNIT, 1L, 0)));
        when(store.findKeyResult(KEY_RESULT)).thenReturn(Optional.of(
                new GoalStore.KeyResultRow(KEY_RESULT, GOAL, "签约客户", BigDecimal.TEN,
                        BigDecimal.ONE, "家", 0, 3L)));

        assertThatThrownBy(() -> service.updateKeyResult(GOAL, KEY_RESULT, UNIT,
                null, BigDecimal.valueOf(2), 2L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("KEY_RESULT_VERSION_CONFLICT");
                });
        verify(store, never()).updateKeyResultDetails(any(), anyString(), any(), anyLong());
    }

    @Test
    void updateKeyResultChangesTitleAndKeepsCurrentValue() {
        when(store.find(GOAL)).thenReturn(Optional.of(teamGoal(UNIT, 1L, 0)));
        when(store.findKeyResult(KEY_RESULT)).thenReturn(
                Optional.of(keyResult(KEY_RESULT, "旧标题", BigDecimal.ONE, 1L)),
                Optional.of(keyResult(KEY_RESULT, "新标题", BigDecimal.ONE, 2L)));
        when(store.updateKeyResultDetails(KEY_RESULT, "新标题", BigDecimal.ONE, 1L))
                .thenReturn(true);

        GoalService.KeyResultView view = service.updateKeyResult(GOAL, KEY_RESULT, UNIT,
                " 新标题 ", null, 1L);

        assertThat(view.title()).isEqualTo("新标题");
        assertThat(view.currentValue()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void updateKeyResultChangesCurrentValueAndKeepsTitle() {
        when(store.find(GOAL)).thenReturn(Optional.of(teamGoal(UNIT, 1L, 0)));
        when(store.findKeyResult(KEY_RESULT)).thenReturn(
                Optional.of(keyResult(KEY_RESULT, "签约客户", BigDecimal.ONE, 1L)),
                Optional.of(keyResult(KEY_RESULT, "签约客户", BigDecimal.TEN, 2L)));
        when(store.updateKeyResultDetails(KEY_RESULT, "签约客户", BigDecimal.TEN, 1L))
                .thenReturn(true);

        GoalService.KeyResultView view = service.updateKeyResult(GOAL, KEY_RESULT, UNIT,
                null, BigDecimal.TEN, 1L);

        assertThat(view.currentValue()).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void updateKeyResultRejectsBlankTitle() {
        assertThatThrownBy(() -> service.updateKeyResult(GOAL, KEY_RESULT, UNIT,
                "  ", null, 1L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(400);
                    assertThat(error.code()).isEqualTo("KEY_RESULT_TITLE_INVALID");
                });
        verify(store, never()).updateKeyResultDetails(any(), anyString(), any(), anyLong());
    }

    @Test
    void updateKeyResultRequiresAtLeastOneField() {
        assertThatThrownBy(() -> service.updateKeyResult(GOAL, KEY_RESULT, UNIT, null, null, 1L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(400);
                    assertThat(error.code()).isEqualTo("KEY_RESULT_UPDATE_EMPTY");
                });
        verify(store, never()).updateKeyResultDetails(any(), anyString(), any(), anyLong());
    }

    @Test
    void updateTeamRejectsGoalFromAnotherUnit() {
        when(store.find(GOAL)).thenReturn(Optional.of(teamGoal(UUID.randomUUID(), 1L, 0)));

        assertThatThrownBy(() -> service.updateTeam(GOAL, UNIT,
                new GoalService.GoalInput("新标题", "", "", null, null, "archived"), 1L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(404);
                    assertThat(error.code()).isEqualTo("GOAL_NOT_FOUND");
                });
        verify(store, never()).update(any(), anyString(), anyString(), anyString(), any(),
                anyInt(), anyString(), anyLong());
    }

    @Test
    void updateTeamStaleVersionIsRejected() {
        when(store.find(GOAL)).thenReturn(Optional.of(teamGoal(UNIT, 3L, 20)));

        assertThatThrownBy(() -> service.updateTeam(GOAL, UNIT,
                new GoalService.GoalInput("新标题", "", "", null, null, null), 2L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("GOAL_VERSION_CONFLICT");
                });
        verify(store, never()).update(any(), anyString(), anyString(), anyString(), any(),
                anyInt(), anyString(), anyLong());
    }

    @Test
    void updateTeamKeepsStoredProgressAndDerivesFromKeyResults() {
        when(store.find(GOAL)).thenReturn(
                Optional.of(teamGoal(UNIT, 1L, 0)),
                Optional.of(teamGoal(UNIT, 2L, 0)));
        when(store.update(any(), anyString(), anyString(), anyString(), any(), anyInt(),
                anyString(), anyLong())).thenReturn(true);
        when(store.listKeyResults(GOAL)).thenReturn(List.of(
                keyResult(BigDecimal.valueOf(100), BigDecimal.valueOf(50), 0)));

        GoalService.GoalView view = service.updateTeam(GOAL, UNIT,
                new GoalService.GoalInput("新标题", "", null, null, 99, "archived"), 1L);

        verify(store).update(GOAL, "新标题", "", "2026-Q4", null, 0, "archived", 1L);
        assertThat(view.progress()).isEqualTo(50);
    }

    @Test
    void deleteKeyResultRejectsKeyResultOfAnotherGoal() {
        when(store.find(GOAL)).thenReturn(Optional.of(teamGoal(UNIT, 1L, 0)));
        when(store.findKeyResult(KEY_RESULT)).thenReturn(Optional.of(
                new GoalStore.KeyResultRow(KEY_RESULT, UUID.randomUUID(), "签约客户",
                        BigDecimal.TEN, BigDecimal.ONE, "家", 0, 1L)));

        assertThatThrownBy(() -> service.deleteKeyResult(GOAL, KEY_RESULT, UNIT))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(404);
                    assertThat(error.code()).isEqualTo("KEY_RESULT_NOT_FOUND");
                });
        verify(store, never()).deleteKeyResult(any());
    }

    @Test
    void deleteKeyResultRemovesRow() {
        when(store.find(GOAL)).thenReturn(Optional.of(teamGoal(UNIT, 1L, 0)));
        when(store.findKeyResult(KEY_RESULT)).thenReturn(Optional.of(
                new GoalStore.KeyResultRow(KEY_RESULT, GOAL, "签约客户", BigDecimal.TEN,
                        BigDecimal.ONE, "家", 0, 1L)));
        when(store.deleteKeyResult(KEY_RESULT)).thenReturn(true);

        service.deleteKeyResult(GOAL, KEY_RESULT, UNIT);

        verify(store).deleteKeyResult(KEY_RESULT);
    }

    @Test
    void moveKeyResultSwapsNeighboursAndNormalizesOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<GoalStore.KeyResultRow> rows = List.of(
                keyResult(first, 0), keyResult(KEY_RESULT, 1), keyResult(second, 2));
        when(store.find(GOAL)).thenReturn(Optional.of(teamGoal(UNIT, 1L, 0)));
        when(store.listKeyResults(GOAL)).thenReturn(rows,
                List.of(keyResult(KEY_RESULT, 0), keyResult(first, 1), keyResult(second, 2)));

        List<GoalService.KeyResultView> moved = service.moveKeyResult(GOAL, KEY_RESULT, UNIT, "up");

        verify(store).updateKeyResultOrder(KEY_RESULT, 0);
        verify(store).updateKeyResultOrder(first, 1);
        assertThat(moved).extracting(GoalService.KeyResultView::id)
                .containsExactly(KEY_RESULT, first, second);
    }

    @Test
    void moveKeyResultAtBoundaryLeavesOrderUntouched() {
        UUID first = UUID.randomUUID();
        when(store.find(GOAL)).thenReturn(Optional.of(teamGoal(UNIT, 1L, 0)));
        when(store.listKeyResults(GOAL)).thenReturn(
                List.of(keyResult(first, 0), keyResult(KEY_RESULT, 1)));

        List<GoalService.KeyResultView> moved = service.moveKeyResult(GOAL, first, UNIT, "up");

        verify(store, never()).updateKeyResultOrder(any(), anyInt());
        assertThat(moved).extracting(GoalService.KeyResultView::id)
                .containsExactly(first, KEY_RESULT);
    }

    @Test
    void moveKeyResultRejectsUnknownDirection() {
        assertThatThrownBy(() -> service.moveKeyResult(GOAL, KEY_RESULT, UNIT, "left"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(400);
                    assertThat(error.code()).isEqualTo("KEY_RESULT_DIRECTION_INVALID");
                });
    }

    private static GoalStore.GoalRow goal(UUID owner, long version) {
        OffsetDateTime now = OffsetDateTime.now();
        return new GoalStore.GoalRow(GOAL, owner, "季度目标", "说明", "2026-Q4", null, 0,
                "active", "person", null, version, now, now);
    }

    private static GoalStore.GoalRow teamGoal(UUID orgUnitId, long version, int progress) {
        OffsetDateTime now = OffsetDateTime.now();
        return new GoalStore.GoalRow(GOAL, PERSON, "部门目标", "", "2026-Q4", null, progress,
                "active", "team", orgUnitId, version, now, now);
    }

    private static GoalStore.KeyResultRow keyResult(BigDecimal target, BigDecimal current,
                                                    int sortOrder) {
        return new GoalStore.KeyResultRow(UUID.randomUUID(), GOAL, "关键结果", target, current,
                "", sortOrder, 1L);
    }

    private static GoalStore.KeyResultRow keyResult(UUID id, int sortOrder) {
        return new GoalStore.KeyResultRow(id, GOAL, "关键结果", BigDecimal.TEN, BigDecimal.ONE,
                "", sortOrder, 1L);
    }

    private static GoalStore.KeyResultRow keyResult(UUID id, String title, BigDecimal current,
                                                    long version) {
        return new GoalStore.KeyResultRow(id, GOAL, title, BigDecimal.TEN, current, "家", 0,
                version);
    }
}
