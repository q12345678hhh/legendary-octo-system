package com.acme.platform.worklog.time;

import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.work.WorkStatusQuery;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 工时状态投影：只返回已提交/已批准（含被退回）的日快照与本周周报状态。
 *
 * <p>草稿不出现在任何管理视图；本人查看草稿走自己的工时页，不走这里。
 */
@Component
public class WorklogStatusQuery implements WorkStatusQuery {

    private final WorklogStore store;
    private final TenantTransactions transactions;

    public WorklogStatusQuery(WorklogStore store, TenantTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    @Override
    public Map<UUID, PersonWorkStatus> statuses(Collection<UUID> personIds, LocalDate date) {
        if (personIds == null || personIds.isEmpty()) {
            return Map.of();
        }
        return transactions.inTenant(context -> {
            Map<UUID, WorklogStore.DayState> days = store.dayStates(personIds, date);
            List<UUID> submissionIds = days.values().stream()
                    .map(WorklogStore.DayState::currentSubmissionId)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            Map<UUID, Integer> totals = store.submittedTotals(submissionIds);
            LocalDate weekStart = date.with(DayOfWeek.MONDAY);
            Map<UUID, String> reportStates = store.reportStates(personIds, weekStart);
            Map<UUID, PersonWorkStatus> result = new HashMap<>();
            for (UUID personId : personIds) {
                WorklogStore.DayState day = days.get(personId);
                Integer minutes = null;
                String state = null;
                if (day != null && day.currentSubmissionId() != null
                        && !"draft".equals(day.state())) {
                    state = day.state();
                    minutes = totals.get(day.currentSubmissionId());
                }
                String reportState = reportStates.get(personId);
                if (minutes != null || state != null || reportState != null) {
                    result.put(personId, new PersonWorkStatus(minutes, state, reportState));
                }
            }
            return Map.copyOf(result);
        });
    }
}
