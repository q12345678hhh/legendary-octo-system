package com.acme.platform;

import com.acme.platform.goal.GoalService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 个人工作页的目标分区：只对本人展示，最多列出前三个进行中的目标。 */
@Component
class WorkPageGoalsSectionProvider implements WorkPageSectionProvider {

    private static final int MAX_ITEMS = 3;

    private final GoalService goalService;

    WorkPageGoalsSectionProvider(GoalService goalService) {
        this.goalService = goalService;
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
                .map(goal -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", goal.id());
                    item.put("title", goal.title());
                    item.put("progress", goal.progress());
                    item.put("dueDate", goal.dueDate());
                    item.put("period", goal.period());
                    item.put("version", goal.version());
                    return item;
                })
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("active", active.size());
        data.put("averageProgress", average);
        data.put("items", items);
        return data;
    }
}
