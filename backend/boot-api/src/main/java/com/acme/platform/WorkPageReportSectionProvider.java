package com.acme.platform;

import com.acme.platform.platform.work.WorkStatusQuery;
import com.acme.platform.worklog.report.WorklogReportService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 个人工作页的周报分区：本人看最新版本，管理范围只看已提交状态。 */
@Component
class WorkPageReportSectionProvider implements WorkPageSectionProvider {

    private final WorklogReportService reports;
    private final WorkStatusQuery statusQuery;

    WorkPageReportSectionProvider(WorklogReportService reports, WorkStatusQuery statusQuery) {
        this.reports = reports;
        this.statusQuery = statusQuery;
    }

    @Override
    public String key() {
        return "report";
    }

    @Override
    public Object load(UUID subjectPersonId, boolean self) {
        LocalDate weekStart = LocalDate.now().with(DayOfWeek.MONDAY);
        if (!self) {
            WorkStatusQuery.PersonWorkStatus status =
                    statusQuery.statuses(List.of(subjectPersonId), LocalDate.now())
                            .get(subjectPersonId);
            if (status == null || status.weekReportState() == null) {
                return null;
            }
            return Map.of("weekStart", weekStart.toString(),
                    "state", status.weekReportState(), "totalMinutes", 0);
        }
        WorklogReportService.ReportView report = reports.latest(subjectPersonId, weekStart);
        if (report == null) {
            return Map.of("weekStart", weekStart.toString(), "state", "none", "totalMinutes", 0);
        }
        int total = report.content().get("days") instanceof List<?> days
                ? days.stream()
                .mapToInt(day -> day instanceof Map<?, ?> map && map.get("minutes") instanceof Number n
                        ? n.intValue() : 0)
                .sum()
                : 0;
        return Map.of("weekStart", weekStart.toString(), "state", report.status(),
                "totalMinutes", total);
    }
}
