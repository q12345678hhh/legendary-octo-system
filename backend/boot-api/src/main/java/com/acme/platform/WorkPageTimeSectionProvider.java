package com.acme.platform;

import com.acme.platform.platform.work.WorkStatusQuery;
import com.acme.platform.worklog.time.WorklogEntry;
import com.acme.platform.worklog.time.WorklogEntryService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 个人工作页的工时分区：本人看草稿，管理范围只看已确认快照。 */
@Component
class WorkPageTimeSectionProvider implements WorkPageSectionProvider {

    private final WorklogEntryService entries;
    private final WorkStatusQuery statusQuery;

    WorkPageTimeSectionProvider(WorklogEntryService entries, WorkStatusQuery statusQuery) {
        this.entries = entries;
        this.statusQuery = statusQuery;
    }

    @Override
    public String key() {
        return "time";
    }

    @Override
    public Object load(UUID subjectPersonId, boolean self) {
        LocalDate today = LocalDate.now();
        if (self) {
            List<WorklogEntry> todayEntries = entries.list(subjectPersonId, today);
            int minutes = todayEntries.stream().mapToInt(WorklogEntry::durationMinutes).sum();
            return Map.of("todayMinutes", minutes,
                    "dayState", todayEntries.isEmpty() ? "empty" : "draft");
        }
        WorkStatusQuery.PersonWorkStatus status =
                statusQuery.statuses(List.of(subjectPersonId), today).get(subjectPersonId);
        if (status == null) {
            return null;
        }
        return Map.of("todayMinutes", status.todayMinutes() == null ? 0 : status.todayMinutes(),
                "dayState", status.dayState() == null ? "submitted" : status.dayState());
    }
}
