package com.acme.platform.worklog.report.summary;

import com.acme.platform.worklog.time.WorklogStore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 从已确认日快照构建周报事实，并计算确定性异常。
 *
 * <p>只读 {@link WorklogStore.Submission#snapshot()}，不查询语音表；
 * 缺少说明、补录、缺勤、明显偏离都由固定规则判断，不交给模型。
 */
public class ReportFactsBuilder {

    private static final int MIN_CONFIRMED_DAY_MINUTES = 60;

    private final ObjectMapper objectMapper;

    public ReportFactsBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WeekFacts build(List<WorklogStore.Submission> submissions,
                           Map<LocalDate, String> dayStates,
                           LocalDate weekStart, LocalDate weekEnd,
                           int maxDailyMinutes, LocalDate today) {
        List<WeekFacts.EntryFact> entries = new ArrayList<>();
        List<WeekFacts.DayFact> days = new ArrayList<>();
        List<WeekFacts.AnomalyFact> anomalies = new ArrayList<>();
        Map<String, List<UUID>> itemIds = new LinkedHashMap<>();
        Map<String, Integer> itemMinutes = new LinkedHashMap<>();
        Map<LocalDate, WorklogStore.Submission> confirmed = new LinkedHashMap<>();

        for (WorklogStore.Submission submission : submissions) {
            confirmed.put(submission.workDate(), submission);
        }

        int totalMinutes = 0;
        for (WorklogStore.Submission submission : submissions) {
            totalMinutes += submission.totalMinutes();
            List<WeekFacts.EntryFact> dayEntries = parse(submission, anomalies);
            entries.addAll(dayEntries);
            List<UUID> entryIds = new ArrayList<>();
            int entryMinutes = 0;
            for (WeekFacts.EntryFact entry : dayEntries) {
                entryIds.add(entry.entryId());
                entryMinutes += entry.minutes();
                String key = entry.projectId() == null ? "unassigned" : "project:" + entry.projectId();
                itemIds.computeIfAbsent(key, unused -> new ArrayList<>()).add(entry.entryId());
                itemMinutes.merge(key, entry.minutes(), Integer::sum);
                if (entry.description() == null || entry.description().isBlank()) {
                    anomalies.add(new WeekFacts.AnomalyFact("EMPTY_DESCRIPTION",
                            "工时条目缺少说明", entry.date(), entry.entryId()));
                }
            }
            if (entryMinutes != submission.totalMinutes()) {
                anomalies.add(new WeekFacts.AnomalyFact("SNAPSHOT_MISMATCH",
                        "日快照明细合计与提交总时长不一致", submission.workDate(), null));
            }
            days.add(new WeekFacts.DayFact(submission.workDate(), submission.totalMinutes(),
                    submission.id(), entryIds));

            int limit = Math.min(maxDailyMinutes, 720);
            if (submission.totalMinutes() > limit) {
                anomalies.add(new WeekFacts.AnomalyFact("LONG_DAY",
                        "单日工时明显偏高：" + submission.totalMinutes() + " 分钟",
                        submission.workDate(), null));
            } else if (submission.totalMinutes() < MIN_CONFIRMED_DAY_MINUTES) {
                anomalies.add(new WeekFacts.AnomalyFact("SHORT_DAY",
                        "单日工时明显偏低：" + submission.totalMinutes() + " 分钟",
                        submission.workDate(), null));
            }
            if (submission.submittedAt() != null
                    && submission.submittedAt().toLocalDate()
                            .isAfter(submission.workDate().plusDays(2))) {
                anomalies.add(new WeekFacts.AnomalyFact("BACKFILLED_DAY",
                        "该日工时在两天后才提交，属于补录", submission.workDate(), null));
            }
        }

        anomalies.addAll(missingDayAnomalies(confirmed, dayStates, weekStart, weekEnd, today));

        List<WeekFacts.ItemFact> items = new ArrayList<>();
        itemMinutes.forEach((key, minutes) ->
                items.add(new WeekFacts.ItemFact(key, minutes,
                        List.copyOf(itemIds.getOrDefault(key, List.of())))));

        return new WeekFacts(weekStart, weekEnd, totalMinutes, submissions.size(),
                List.copyOf(entries), List.copyOf(items), List.copyOf(days),
                List.copyOf(anomalies), false);
    }

    private List<WeekFacts.AnomalyFact> missingDayAnomalies(
            Map<LocalDate, WorklogStore.Submission> confirmed,
            Map<LocalDate, String> dayStates,
            LocalDate weekStart, LocalDate weekEnd, LocalDate today) {
        List<WeekFacts.AnomalyFact> anomalies = new ArrayList<>();
        LocalDate last = weekEnd.isAfter(today) ? today : weekEnd;
        for (LocalDate date = weekStart; !date.isAfter(last); date = date.plusDays(1)) {
            if (date.getDayOfWeek().getValue() > 5 || confirmed.containsKey(date)) {
                continue;
            }
            String state = dayStates.get(date);
            if (state == null || "empty".equals(state)) {
                anomalies.add(new WeekFacts.AnomalyFact("MISSING_DAY",
                        "工作日未提交工时", date, null));
            } else if (!"submitted".equals(state) && !"approved".equals(state)) {
                anomalies.add(new WeekFacts.AnomalyFact("UNCONFIRMED_DAY",
                        "当日工时未确认，状态：" + state, date, null));
            } else {
                anomalies.add(new WeekFacts.AnomalyFact("MISSING_DAY",
                        "当日状态为已提交但缺少日快照", date, null));
            }
        }
        return anomalies;
    }

    private List<WeekFacts.EntryFact> parse(WorklogStore.Submission submission,
                                            List<WeekFacts.AnomalyFact> anomalies) {
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(submission.snapshot(),
                    new TypeReference<List<Map<String, Object>>>() { });
            List<WeekFacts.EntryFact> entries = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object entryId = row.get("entryId");
                Object minutes = row.get("minutes");
                if (entryId == null || !(minutes instanceof Number number)) {
                    anomalies.add(new WeekFacts.AnomalyFact("SNAPSHOT_INVALID",
                            "日快照存在无法解析的条目", submission.workDate(), null));
                    continue;
                }
                entries.add(new WeekFacts.EntryFact(
                        UUID.fromString(String.valueOf(entryId)),
                        submission.workDate(),
                        number.intValue(),
                        uuid(row.get("projectId")),
                        uuid(row.get("taskId")),
                        row.get("description") == null ? "" : String.valueOf(row.get("description"))));
            }
            return entries;
        } catch (RuntimeException e) {
            anomalies.add(new WeekFacts.AnomalyFact("SNAPSHOT_INVALID",
                    "日快照无法解析", submission.workDate(), null));
            return List.of();
        }
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(String.valueOf(value));
    }
}
