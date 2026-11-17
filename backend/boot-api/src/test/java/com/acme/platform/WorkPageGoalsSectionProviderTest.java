package com.acme.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AccessScope;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.DecisionReason;
import com.acme.platform.goal.GoalService;
import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 工作页目标分区：团队目标必须同时满足主岗部门与团队目标读权限。 */
class WorkPageGoalsSectionProviderTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID TEAM_GOAL = UUID.randomUUID();

    private GoalService goalService;
    private OrganizationScopeQueries organizationScopes;
    private AuthorizationGuard authorization;
    private WorkPageGoalsSectionProvider provider;

    @BeforeEach
    void setUp() {
        goalService = mock(GoalService.class);
        organizationScopes = mock(OrganizationScopeQueries.class);
        authorization = mock(AuthorizationGuard.class);
        TenantTransactions transactions = mock(TenantTransactions.class);
        when(transactions.inTenant(any(Function.class))).thenAnswer(invocation -> {
            Function<RequestContext, Object> work = invocation.getArgument(0);
            return work.apply(new RequestContext(TENANT, "actor", "corr", "trace"));
        });
        provider = new WorkPageGoalsSectionProvider(goalService, organizationScopes, transactions,
                authorization, Clock.systemUTC());
    }

    @Test
    void includesTeamGoalsWhenDepartmentReadIsAllowed() {
        when(organizationScopes.primaryUnitOfPerson(eq(PERSON), any(LocalDate.class)))
                .thenReturn(Optional.of(UNIT));
        when(authorization.decide(eq("work.goal.team.read"), eq("org.unit"), eq(UNIT), anyMap()))
                .thenReturn(decision(true));
        when(goalService.listOwn(PERSON)).thenReturn(List.of(
                goal(UUID.randomUUID(), null, "Grow customer success", 60, List.of())));
        when(goalService.listTeam(UNIT)).thenReturn(List.of(
                goal(TEAM_GOAL, UNIT, "Q4 renewal sprint", 65, List.of(
                        keyResult(BigDecimal.valueOf(100), BigDecimal.valueOf(80)),
                        keyResult(BigDecimal.TEN, BigDecimal.valueOf(5)))),
                archivedGoal(UUID.randomUUID(), UNIT, "旧目标", 10)));

        Map<String, Object> data = (Map<String, Object>) provider.load(PERSON, true);

        assertThat(data.get("teamActive")).isEqualTo(1);
        assertThat(data.get("teamAverageProgress")).isEqualTo(65);
        assertThat((List<?>) data.get("teamGoals")).hasSize(1);
        assertThat((List<?>) data.get("items")).hasSize(1);
    }

    @Test
    void omitsTeamGoalsWhenDepartmentReadIsDenied() {
        when(organizationScopes.primaryUnitOfPerson(eq(PERSON), any(LocalDate.class)))
                .thenReturn(Optional.of(UNIT));
        when(authorization.decide(eq("work.goal.team.read"), eq("org.unit"), eq(UNIT), anyMap()))
                .thenReturn(decision(false));
        when(goalService.listOwn(PERSON)).thenReturn(List.of(
                goal(UUID.randomUUID(), null, "Grow customer success", 60, List.of())));

        Map<String, Object> data = (Map<String, Object>) provider.load(PERSON, true);

        assertThat((List<?>) data.get("items")).hasSize(1);
        assertThat(data.get("teamActive")).isEqualTo(0);
        assertThat(data.get("teamAverageProgress")).isEqualTo(0);
        assertThat((List<?>) data.get("teamGoals")).isEmpty();
        verify(goalService, never()).listTeam(any());
    }

    @Test
    void returnsNothingWhenViewingSomeoneElse() {
        assertThat(provider.load(PERSON, false)).isNull();
        verify(goalService, never()).listOwn(any());
    }

    private static AccessDecision decision(boolean allowed) {
        return new AccessDecision(allowed,
                allowed ? DecisionReason.ALLOWED : DecisionReason.SCOPE_MISS,
                List.of(), AccessScope.none(), Map.of(), 1L, OffsetDateTime.now(), false);
    }

    private static GoalService.GoalView goal(UUID id, UUID orgUnitId, String title, int progress,
                                             List<GoalService.KeyResultView> keyResults) {
        return goal(id, orgUnitId, title, progress, "active", keyResults);
    }

    private static GoalService.GoalView archivedGoal(UUID id, UUID orgUnitId, String title,
                                                     int progress) {
        return goal(id, orgUnitId, title, progress, "archived", List.of());
    }

    private static GoalService.GoalView goal(UUID id, UUID orgUnitId, String title, int progress,
                                             String status,
                                             List<GoalService.KeyResultView> keyResults) {
        OffsetDateTime now = OffsetDateTime.now();
        return new GoalService.GoalView(id, PERSON, orgUnitId == null ? "personal" : "team",
                orgUnitId, title, "", "2026-Q4", null, progress, status, 1L, now, now,
                keyResults);
    }

    private static GoalService.KeyResultView keyResult(BigDecimal target, BigDecimal current) {
        return new GoalService.KeyResultView(UUID.randomUUID(), TEAM_GOAL, "关键结果", target,
                current, "", 0, 1L);
    }
}
