package com.acme.platform;

import com.acme.platform.goal.GoalService;
import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 个人工作页的目标分区：本人目标可维护，所在部门团队目标只读展示。 */
@Component
class WorkPageGoalsSectionProvider implements WorkPageSectionProvider {

    private static final int MAX_ITEMS = 3;

    private final GoalService goalService;
    private final OrganizationScopeQueries organizationScopes;
    private final TenantTransactions transactions;
    private final Clock clock;

    WorkPageGoalsSectionProvider(GoalService goalService,
                                 OrganizationScopeQueries organizationScopes,
                                 TenantTransactions transactions,
                                 Clock clock) {
        this.goalService = goalService;
        this.organizationScopes = organizationScopes;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public String key() {
        return "goals";
    }

    @Override
    public Object load(UUID subjectPersonId, boolean self) {
        if (!self) {
            return null;
        }
        List<GoalService.GoalView> goals = goalService.listOwn(subjectPersonId);
        List<GoalService.GoalView> active = goals.stream()
                .filter(goal -> "active".equals(goal.status()))
                .toList();
        int average = active.isEmpty() ? 0
                : (int) Math.round(active.stream().mapToInt(GoalService.GoalView::progress)
                        .average().orElse(0));
        List<Map<String, Object>> items = active.stream()
                .limit(MAX_ITEMS)
                .map(WorkPageGoalsSectionProvider::personalItem)
                .toList();
        List<GoalService.GoalView> teamActive = teamActiveGoals(subjectPersonId);
        int teamAverage = teamActive.isEmpty() ? 0
                : (int) Math.round(teamActive.stream()
                        .mapToInt(GoalService.GoalView::progress).average().orElse(0));
        List<Map<String, Object>> teamItems = teamActive.stream()
                .limit(MAX_ITEMS)
                .map(WorkPageGoalsSectionProvider::teamItem)
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("active", active.size());
        data.put("averageProgress", average);
        data.put("items", items);
        data.put("teamActive", teamActive.size());
        data.put("teamAverageProgress", teamAverage);
        data.put("teamGoals", teamItems);
        return data;
    }

    /** 部门团队目标需要组织范围：先在租户事务里定位主岗部门，再取该部门的进行中目标。 */
    private List<GoalService.GoalView> teamActiveGoals(UUID subjectPersonId) {
        return transactions.inTenant(context -> {
            LocalDate today = LocalDate.now(clock);
            return organizationScopes.primaryUnitOfPerson(subjectPersonId, today)
                    .map(unitId -> goalService.listTeam(unitId).stream()
                            .filter(goal -> "active".equals(goal.status()))
                            .toList())
                    .orElseGet(List::of);
        });
    }

    private static Map<String, Object> personalItem(GoalService.GoalView goal) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", goal.id());
        item.put("title", goal.title());
        item.put("progress", goal.progress());
        item.put("dueDate", goal.dueDate());
        item.put("period", goal.period());
        item.put("version", goal.version());
        return item;
    }

    private static Map<String, Object> teamItem(GoalService.GoalView goal) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", goal.id());
        item.put("title", goal.title());
        item.put("progress", goal.progress());
        item.put("dueDate", goal.dueDate());
        item.put("period", goal.period());
        item.put("status", goal.status());
        item.put("keyResultCount", goal.keyResults().size());
        return item;
    }
}
