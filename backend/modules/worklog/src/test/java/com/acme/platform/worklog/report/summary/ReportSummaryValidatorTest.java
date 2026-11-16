package com.acme.platform.worklog.report.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportSummaryValidatorTest {

    private static final UUID ENTRY = UUID.fromString("11111111-0000-4000-8000-000000000101");
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);

    private final ReportSummaryValidator validator = new ReportSummaryValidator();

    @Test
    void keepsClaimsWithTraceableNumbersAndValidEntries() {
        ReportSummarizer.SummaryDraft draft = draft(List.of(
                point("完成客户方案交付，投入 2 小时。", ENTRY),
                point("本周确认工时 120 分钟。", ENTRY),
                point("补充联调与验收。", ENTRY)));

        ReportSummaryValidator.ValidatedSummary validated = validator.validate(draft, facts());

        assertThat(validated.summaryPoints()).hasSize(3);
        assertThat(validated.droppedClaims()).isZero();
    }

    @Test
    void dropsSentencesWithInventedNumbers() {
        ReportSummaryValidator.ValidatedSummary validated = validator.validate(
                draft(List.of(point("完成客户方案交付。提前完成 3 个里程碑。", ENTRY))), facts());

        assertThat(validated.summaryPoints()).hasSize(1);
        assertThat(validated.summaryPoints().getFirst().text()).isEqualTo("完成客户方案交付。");
    }

    @Test
    void dropsClaimsWithInvalidEntryReferences() {
        ReportSummaryValidator.ValidatedSummary validated = validator.validate(
                draft(List.of(point("完成未被记录的交付。", UUID.randomUUID()))), facts());

        assertThat(validated.summaryPoints()).isEmpty();
        assertThat(validated.droppedClaims()).isEqualTo(1);
    }

    @Test
    void dropsChineseQuantitiesThatCannotBeTraced() {
        ReportSummaryValidator.ValidatedSummary validated = validator.validate(
                draft(List.of(point("节省两天时间。完成客户方案交付。", ENTRY))), facts());

        assertThat(validated.summaryPoints().getFirst().text()).isEqualTo("完成客户方案交付。");
    }

    @Test
    void returnsEmptyWhenEverythingIsDropped() {
        ReportSummarizer.SummaryDraft draft = new ReportSummarizer.SummaryDraft(
                List.of(point("虚构产出 99 项。", ENTRY)), List.of(), "");

        ReportSummaryValidator.ValidatedSummary validated = validator.validate(draft, facts());

        assertThat(validated.isEmpty()).isTrue();
        assertThat(validated.droppedClaims()).isEqualTo(1);
    }

    private static ReportSummarizer.SummaryPoint point(String text, UUID entryId) {
        return new ReportSummarizer.SummaryPoint(text, List.of(entryId));
    }

    private static ReportSummarizer.SummaryDraft draft(
            List<ReportSummarizer.SummaryPoint> points) {
        return new ReportSummarizer.SummaryDraft(points, List.of(), "下周补充联调。");
    }

    static WeekFacts facts() {
        return new WeekFacts(MONDAY, MONDAY.plusDays(6), 120, 1,
                List.of(new WeekFacts.EntryFact(ENTRY, MONDAY, 120, null, null, "客户方案")),
                List.of(new WeekFacts.ItemFact("unassigned", 120, List.of(ENTRY))),
                List.of(new WeekFacts.DayFact(MONDAY, 120, UUID.randomUUID(), List.of(ENTRY))),
                List.of(), false);
    }
}
