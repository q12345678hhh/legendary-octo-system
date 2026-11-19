package com.acme.platform.worklog.report.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.platform.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiCompatibleSummarizerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void unconfiguredReturns503() {
        OpenAiCompatibleSummarizer summarizer = new OpenAiCompatibleSummarizer(
                new ReportAiProperties("", "", null, null, null, null), new ObjectMapper());

        assertThat(summarizer.configured()).isFalse();
        assertThatThrownBy(() -> summarizer.summarize(facts()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.code()).isEqualTo("REPORT_AI_NOT_CONFIGURED");
                });
    }

    @Test
    void parsesFencedJsonAndSendsChatCompletionsRequest() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = "```json\\n{\\\"summaryPoints\\\":[{\\\"text\\\":\\\"完成客户方案交付\\\","
                    + "\\\"entryIds\\\":[\\\"11111111-0000-4000-8000-000000000101\\\"]}],"
                    + "\\\"highlights\\\":[],\\\"nextWeekPlan\\\":\\\"下周补充联调\\\"}\\n```";
            String response = "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        OpenAiCompatibleSummarizer summarizer = new OpenAiCompatibleSummarizer(
                properties("http://127.0.0.1:" + server.getAddress().getPort()),
                new ObjectMapper());
        ReportSummarizer.SummaryDraft draft = summarizer.summarize(facts());

        assertThat(draft.summaryPoints()).hasSize(1);
        assertThat(draft.summaryPoints().getFirst().text()).isEqualTo("完成客户方案交付");
        assertThat(draft.nextWeekPlan()).isEqualTo("下周补充联调");
        assertThat(body.get()).contains("\"response_format\":{\"type\":\"json_object\"}");
        assertThat(body.get()).contains("entryId");
    }

    @Test
    void upstreamFailureMapsTo502() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        OpenAiCompatibleSummarizer summarizer = new OpenAiCompatibleSummarizer(
                properties("http://127.0.0.1:" + server.getAddress().getPort()),
                new ObjectMapper());

        assertThatThrownBy(() -> summarizer.summarize(facts()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(502);
                    assertThat(e.code()).isEqualTo("REPORT_AI_UPSTREAM_FAILED");
                });
    }

    @Test
    void emptyOutputMapsTo422() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        OpenAiCompatibleSummarizer summarizer = new OpenAiCompatibleSummarizer(
                properties("http://127.0.0.1:" + server.getAddress().getPort()),
                new ObjectMapper());

        assertThatThrownBy(() -> summarizer.summarize(facts()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("REPORT_AI_INVALID_OUTPUT");
                });
    }

    static WeekFacts facts() {
        UUID entryId = UUID.fromString("11111111-0000-4000-8000-000000000101");
        LocalDate monday = LocalDate.of(2026, 11, 9);
        return new WeekFacts(monday, monday.plusDays(6), 120, 1,
                List.of(new WeekFacts.EntryFact(entryId, monday, 120, null, null, "客户方案")),
                List.of(new WeekFacts.ItemFact("unassigned", 120, List.of(entryId))),
                List.of(new WeekFacts.DayFact(monday, 120, UUID.randomUUID(), List.of(entryId))),
                List.of(), false);
    }

    private static ReportAiProperties properties(String baseUrl) {
        return new ReportAiProperties(baseUrl, "test-key", "test-model", Duration.ofSeconds(5),
                8000, "wp04-02.test");
    }
}
