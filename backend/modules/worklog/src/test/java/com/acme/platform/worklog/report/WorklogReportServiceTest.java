package com.acme.platform.worklog.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.worklog.report.summary.ReportFactsBuilder;
import com.acme.platform.worklog.report.summary.ReportSummarizer;
import com.acme.platform.worklog.report.summary.ReportSummaryGenerator;
import com.acme.platform.worklog.report.summary.ReportSummaryValidator;
import com.acme.platform.worklog.report.summary.WeekFacts;
import com.acme.platform.worklog.report.summary.ReportAiProperties;
import com.acme.platform.worklog.time.WorklogStore;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class WorklogReportServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);
    private static final UUID PERSON = UUID.randomUUID();
    private static final UUID REPORT = UUID.randomUUID();
    private static final UUID ENTRY = UUID.fromString("11111111-0000-4000-8000-000000000101");
    private static final UUID PROJECT = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorklogStore store;
    private TenantTransactions transactions;
    private ReportSummaryGenerator generator;
    private WorklogReportService service;

    @BeforeEach
    void setUp() {
        store = mock(WorklogStore.class);
        transactions = mock(TenantTransactions.class);
        when(transactions.inTenant(any())).thenAnswer(invocation -> {
            Function<RequestContext, Object> work = invocation.getArgument(0);
            return work.apply(mock(RequestContext.class));
        });

        ReportAiProperties properties = new ReportAiProperties("http://localhost", "key", "model",
                Duration.ofSeconds(5), 8000, "wp04-02.test");
        ReportSummarizer summarizer = new ReportSummarizer() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public ReportSummarizer.SummaryDraft summarize(WeekFacts facts) {
                return new ReportSummarizer.SummaryDraft(
                        List.of(new ReportSummarizer.SummaryPoint("完成客户方案交付。",
                                List.of(ENTRY))),
                        List.of(), "下周补充联调。");
            }
        };
        generator = new ReportSummaryGenerator(summarizer, new ReportSummaryValidator(),
                properties);
        service = new WorklogReportService(store, transactions, objectMapper,
                new ReportFactsBuilder(objectMapper), generator);
    }

    @Test
    void regeneratingDraftKeepsEmployeeEditedSummaryAndPlan() {
        WorklogStore.Submission submission = submission();
        when(store.submissionsInWeek(PERSON, MONDAY, MONDAY.plusDays(6)))
                .thenReturn(List.of(submission));
        when(store.dayStatesInRange(PERSON, MONDAY, MONDAY.plusDays(6)))
                .thenReturn(Map.of(MONDAY, "submitted"));
        when(store.setting()).thenReturn(new WorklogStore.Setting(5, 1440));
        WorklogStore.Report draft = draft(contentWithEmployeeEdits());
        when(store.latestReport(PERSON, MONDAY)).thenReturn(Optional.of(draft));
        when(store.report(REPORT)).thenReturn(Optional.of(draft));

        service.generate(PERSON, MONDAY);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(store).updateReportContent(any(), content.capture());
        Map<String, Object> saved = objectMapper.readValue(content.getValue(),
                new TypeReference<Map<String, Object>>() { });
        assertThat(saved.get("summary")).isEqualTo("员工摘要");
        assertThat(saved.get("summarySource")).isEqualTo("employee");
        assertThat(saved.get("nextWeekPlan")).isEqualTo("员工计划");
        assertThat(saved.get("nextWeekPlanSource")).isEqualTo("employee");
        assertThat(saved.get("issues")).isEqualTo("员工问题");
        assertThat(saved.get("highlights")).isEqualTo(List.of());
        assertThat(((Map<?, ?>) saved.get("generation")).get("mode")).isEqualTo("ai");
    }

    @Test
    void submittedReportCannotBeRegenerated() {
        WorklogStore.Submission submission = submission();
        when(store.submissionsInWeek(PERSON, MONDAY, MONDAY.plusDays(6)))
                .thenReturn(List.of(submission));
        when(store.latestReport(PERSON, MONDAY)).thenReturn(Optional.of(
                new WorklogStore.Report(REPORT, PERSON, MONDAY, MONDAY.plusDays(6), 1,
                        "submitted", "{}", OffsetDateTime.now())));

        assertThatThrownBy(() -> service.generate(PERSON, MONDAY))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("REPORT_ALREADY_SUBMITTED"));
        verify(store, never()).updateReportContent(any(), any());
    }

    @Test
    void submitWritesSourcesBeforeFreezingReport() {
        WorklogStore.Report draft = draft(contentWithEmployeeEdits());
        when(store.latestReport(PERSON, MONDAY)).thenReturn(Optional.of(draft));
        when(store.report(REPORT)).thenReturn(Optional.of(draft));

        service.submit(PERSON, MONDAY);

        InOrder order = inOrder(store);
        order.verify(store).replaceReportSources(any(), any());
        order.verify(store).submitReport(any(), any());
    }

    private WorklogStore.Submission submission() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("entryId", ENTRY.toString());
        entry.put("minutes", 120);
        entry.put("projectId", PROJECT.toString());
        entry.put("taskId", null);
        entry.put("description", "客户方案");
        String snapshot = objectMapper.writeValueAsString(List.of(entry));
        return new WorklogStore.Submission(UUID.randomUUID(), PERSON, MONDAY, 1, "submitted",
                snapshot, 120, OffsetDateTime.parse("2026-11-09T18:00:00+08:00"));
    }

    private WorklogStore.Report draft(String content) {
        return new WorklogStore.Report(REPORT, PERSON, MONDAY, MONDAY.plusDays(6), 1,
                "draft", content, null);
    }

    private String contentWithEmployeeEdits() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("schemaVersion", 2);
        content.put("summary", "员工摘要");
        content.put("summaryPoints", List.of());
        content.put("summarySource", "employee");
        content.put("nextWeekPlan", "员工计划");
        content.put("nextWeekPlanSource", "employee");
        content.put("issues", "员工问题");
        content.put("days", List.of());
        return objectMapper.writeValueAsString(content);
    }
}
