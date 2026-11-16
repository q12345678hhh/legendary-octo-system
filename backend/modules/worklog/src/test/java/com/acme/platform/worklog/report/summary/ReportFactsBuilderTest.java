package com.acme.platform.worklog.report.summary;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.platform.worklog.time.WorklogStore;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReportFactsBuilderTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);
    private static final LocalDate WEEK_END = MONDAY.plusDays(6);
    private static final UUID PROJECT_A = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID PROJECT_B = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReportFactsBuilder builder = new ReportFactsBuilder(objectMapper);

    @Test
    void reportsMissingUnconfirmedButSkipsFutureAndWeekend() {
        List<WorklogStore.Submission> submissions = List.of(
                submission(MONDAY, 480, List.of(
                        entry(PROJECT_A, 240, "客户方案"),
                        entry(PROJECT_B, 240, "内部联调")), 480));
        Map<LocalDate, String> states = Map.of(
                MONDAY, "submitted",
                MONDAY.plusDays(1), "returned",
                MONDAY.plusDays(2), "draft");

        WeekFacts facts = builder.build(submissions, states, MONDAY, WEEK_END, 1440,
                MONDAY.plusDays(2));

        assertThat(facts.confirmedDays()).isEqualTo(1);
        assertThat(facts.totalMinutes()).isEqualTo(480);
        assertThat(facts.items()).extracting(WeekFacts.ItemFact::key)
                .containsExactlyInAnyOrder("project:" + PROJECT_A, "project:" + PROJECT_B);
        assertThat(facts.anomalies()).extracting(WeekFacts.AnomalyFact::code)
                .containsExactly("UNCONFIRMED_DAY", "UNCONFIRMED_DAY");
        assertThat(facts.anomalies()).extracting(WeekFacts.AnomalyFact::date)
                .containsExactly(MONDAY.plusDays(1), MONDAY.plusDays(2));
    }

    @Test
    void flagsLongShortBlankBackfilledAndMismatchedSnapshots() {
        List<WorklogStore.Submission> submissions = List.of(
                submission(MONDAY, 800, List.of(entry(PROJECT_A, 480, "客户方案")), 800,
                        OffsetDateTime.parse("2026-11-12T10:00:00+08:00")),
                submission(MONDAY.plusDays(1), 30,
                        List.of(entry(null, 30, " ")), 30));

        WeekFacts facts = builder.build(submissions, Map.of(
                        MONDAY, "submitted", MONDAY.plusDays(1), "submitted"),
                MONDAY, WEEK_END, 1440, MONDAY.plusDays(4));

        List<String> codes = new ArrayList<>(facts.anomalies().stream()
                .map(WeekFacts.AnomalyFact::code).toList());
        assertThat(codes).contains("LONG_DAY", "SHORT_DAY", "EMPTY_DESCRIPTION",
                "BACKFILLED_DAY", "SNAPSHOT_MISMATCH");
        assertThat(facts.items()).extracting(WeekFacts.ItemFact::key)
                .contains("unassigned");
    }

    @Test
    void invalidSnapshotDoesNotBreakAggregation() {
        WorklogStore.Submission broken = new WorklogStore.Submission(UUID.randomUUID(),
                UUID.randomUUID(), MONDAY, 1, "submitted", "not-json", 120, null);

        WeekFacts facts = builder.build(List.of(broken),
                Map.of(MONDAY, "submitted"), MONDAY, WEEK_END, 1440, MONDAY.plusDays(4));

        assertThat(facts.totalMinutes()).isEqualTo(120);
        assertThat(facts.entries()).isEmpty();
        assertThat(facts.anomalies()).extracting(WeekFacts.AnomalyFact::code)
                .contains("SNAPSHOT_INVALID", "SNAPSHOT_MISMATCH");
    }

    private WorklogStore.Submission submission(LocalDate date, int total,
                                               List<Map<String, Object>> entries,
                                               int snapshotTotal) {
        return submission(date, total, entries, snapshotTotal, null);
    }

    private WorklogStore.Submission submission(LocalDate date, int total,
                                               List<Map<String, Object>> entries,
                                               int snapshotTotal,
                                               OffsetDateTime submittedAt) {
        String snapshot = entries.isEmpty() ? "[]"
                : objectMapper.writeValueAsString(entries);
        return new WorklogStore.Submission(UUID.randomUUID(), UUID.randomUUID(), date, 1,
                "submitted", snapshot, total, submittedAt);
    }

    private static Map<String, Object> entry(UUID projectId, int minutes, String description) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entryId", UUID.randomUUID().toString());
        row.put("minutes", minutes);
        row.put("projectId", projectId == null ? null : projectId.toString());
        row.put("taskId", null);
        row.put("description", description);
        return row;
    }
}
