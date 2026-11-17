package com.acme.platform;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 个人工作页的任务分区：assignee 的任务与 owner 的行动项合并计数。 */
@Component
class WorkPageTasksSectionProvider implements WorkPageSectionProvider {

    private static final int DUE_SOON_DAYS = 7;

    private final WorkPageWorkStore store;

    WorkPageTasksSectionProvider(WorkPageWorkStore store) {
        this.store = store;
    }

    @Override
    public String key() {
        return "tasks";
    }

    @Override
    public Object load(UUID subjectPersonId, boolean self) {
        WorkPageWorkStore.TaskSummary summary =
                store.taskSummary(subjectPersonId, LocalDate.now().plusDays(DUE_SOON_DAYS));
        return Map.of("open", summary.open(), "dueSoon", summary.dueSoon());
    }
}
