package com.acme.platform.worklog.report.summary;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.platform.platform.ApiException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportSummaryGeneratorTest {

    private static final UUID ENTRY = UUID.fromString("11111111-0000-4000-8000-000000000101");
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);
    private static final ReportAiProperties PROPERTIES =
            new ReportAiProperties("http://localhost", "key", "model", Duration.ofSeconds(5),
                    8000, "wp04-02.test");

    @Test
    void unconfiguredDegradesToDurationTemplate() {
        ReportSummaryGenerator generator = generator(false, facts ->
                new ReportSummarizer.SummaryDraft(List.of(), List.of(), ""));

        ReportSummaryGenerator.Outcome outcome = generator.generate(facts());

        assertThat(outcome.aiUsed()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo("AI_NOT_CONFIGURED");
        assertThat(outcome.summaryPoints()).hasSize(1);
        assertThat(outcome.summaryPoints().getFirst().text()).contains("2.0 小时");
    }

    @Test
    void keepsValidAiSummary() {
        ReportSummaryGenerator generator = generator(true, facts ->
                new ReportSummarizer.SummaryDraft(
                        List.of(new ReportSummarizer.SummaryPoint("完成客户方案交付。", List.of(ENTRY))),
                        List.of(), "下周补充联调。"));

        ReportSummaryGenerator.Outcome outcome = generator.generate(facts());

        assertThat(outcome.aiUsed()).isTrue();
        assertThat(outcome.fallbackReason()).isNull();
        assertThat(outcome.summaryPoints()).hasSize(1);
        assertThat(outcome.nextWeekPlan()).isEqualTo("下周补充联调。");
    }

    @Test
    void upstreamFailureDegradesWithoutThrowing() {
        ReportSummaryGenerator generator = generator(true, facts -> {
            throw new ApiException(502, "REPORT_AI_UPSTREAM_FAILED", "down");
        });

        ReportSummaryGenerator.Outcome outcome = generator.generate(facts());

        assertThat(outcome.aiUsed()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo("AI_FAILED");
    }

    @Test
    void allDroppedClaimsDegradeWithReason() {
        ReportSummaryGenerator generator = generator(true, facts ->
                new ReportSummarizer.SummaryDraft(
                        List.of(new ReportSummarizer.SummaryPoint("虚构产出 99 项。", List.of(ENTRY))),
                        List.of(), ""));

        ReportSummaryGenerator.Outcome outcome = generator.generate(facts());

        assertThat(outcome.aiUsed()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo("ALL_CLAIMS_DROPPED");
        assertThat(outcome.droppedClaims()).isEqualTo(1);
    }

    private static ReportSummaryGenerator generator(boolean configured,
                                                    java.util.function.Function<WeekFacts,
                                                            ReportSummarizer.SummaryDraft> draft) {
        ReportSummarizer fake = new ReportSummarizer() {
            @Override
            public boolean configured() {
                return configured;
            }

            @Override
            public SummaryDraft summarize(WeekFacts facts) {
                return draft.apply(facts);
            }
        };
        return new ReportSummaryGenerator(fake, new ReportSummaryValidator(), PROPERTIES);
    }

    static WeekFacts facts() {
        return new WeekFacts(MONDAY, MONDAY.plusDays(6), 120, 1,
                List.of(new WeekFacts.EntryFact(ENTRY, MONDAY, 120, null, null, "客户方案")),
                List.of(new WeekFacts.ItemFact("unassigned", 120, List.of(ENTRY))),
                List.of(new WeekFacts.DayFact(MONDAY, 120, UUID.randomUUID(), List.of(ENTRY))),
                List.of(), false);
    }
}
