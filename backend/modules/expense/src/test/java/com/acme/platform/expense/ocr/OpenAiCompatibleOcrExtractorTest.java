package com.acme.platform.expense.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiCompatibleOcrExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void unconfiguredReturnsUnavailableWithoutHttpCall() {
        OpenAiCompatibleOcrExtractor extractor = new OpenAiCompatibleOcrExtractor(
                new OcrProperties("", "", null, null, null, null), objectMapper);

        Extraction extraction = extractor.extract(new byte[]{1}, "image/png", "invoice.png");

        assertThat(extractor.configured()).isFalse();
        assertThat(extraction.status()).isEqualTo(OcrStatus.UNAVAILABLE);
        assertThat(extraction.errorCode()).isEqualTo("OCR_NOT_CONFIGURED");
        assertThat(extraction.usableForAutoReview()).isFalse();
    }

    @Test
    void pdfGoesToManualWithoutCallingUpstream() throws IOException {
        AtomicReference<String> calls = new AtomicReference<>("none");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.set("called");
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        OpenAiCompatibleOcrExtractor extractor = new OpenAiCompatibleOcrExtractor(
                properties("http://127.0.0.1:" + server.getAddress().getPort()), objectMapper);

        Extraction extraction = extractor.extract(new byte[]{1}, "application/pdf", "invoice.pdf");

        assertThat(extraction.status()).isEqualTo(OcrStatus.PDF_UNSUPPORTED);
        assertThat(calls.get()).isEqualTo("none");
    }

    @Test
    void parsesJsonAndSendsVisionRequest() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        String content = """
                {"amount":128.5,"currency":"CNY","invoiceCode":"044001900111",
                 "invoiceNo":"88000011","vendorName":"云图科技","vendorTaxNo":"91310000XYZ",
                 "occurredOn":"2026-11-10","category":"差旅","title":"上海云图科技有限公司",
                 "lines":[{"description":"高铁票","amount":128.5}],
                 "ocrText":"上海云图科技有限公司 128.50",
                 "confidence":{"amount":0.98,"currency":0.99,"invoiceNo":0.96,
                   "occurredOn":0.95,"vendorName":0.93}}
                """;
        server = respondWith(body, content);

        OpenAiCompatibleOcrExtractor extractor = new OpenAiCompatibleOcrExtractor(
                properties("http://127.0.0.1:" + server.getAddress().getPort()), objectMapper);

        Extraction extraction = extractor.extract(new byte[]{9, 8, 7}, "image/png", "a.png");

        assertThat(extraction.status()).isEqualTo(OcrStatus.OK);
        assertThat(extraction.fields().amount()).isEqualByComparingTo("128.5");
        assertThat(extraction.fields().occurredOn()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(extraction.fields().lines()).hasSize(1);
        assertThat(extraction.confidenceOf("amount")).isEqualTo(0.98);
        assertThat(extraction.ocrText()).contains("上海云图");
        assertThat(body.get()).contains("\"response_format\":{\"type\":\"json_object\"}");
        assertThat(body.get()).contains("data:image/png;base64,");
    }

    @Test
    void upstreamFailureMapsToUnavailable() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.sendResponseHeaders(502, -1);
            exchange.close();
        });
        server.start();

        OpenAiCompatibleOcrExtractor extractor = new OpenAiCompatibleOcrExtractor(
                properties("http://127.0.0.1:" + server.getAddress().getPort()), objectMapper);

        Extraction extraction = extractor.extract(new byte[]{1}, "image/jpeg", "a.jpg");

        assertThat(extraction.status()).isEqualTo(OcrStatus.UNAVAILABLE);
        assertThat(extraction.errorCode()).isEqualTo("OCR_HTTP_502");
    }

    @Test
    void invalidJsonMapsToInvalid() throws IOException {
        server = respondWith(new AtomicReference<>(), "这不是 JSON");

        OpenAiCompatibleOcrExtractor extractor = new OpenAiCompatibleOcrExtractor(
                properties("http://127.0.0.1:" + server.getAddress().getPort()), objectMapper);

        Extraction extraction = extractor.extract(new byte[]{1}, "image/png", "a.png");

        assertThat(extraction.status()).isEqualTo(OcrStatus.INVALID);
        assertThat(extraction.errorCode()).isEqualTo("OCR_OUTPUT_INVALID");
    }

    @Test
    void timeoutMapsToTimeout() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        OcrProperties properties = new OcrProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "key", "model",
                Duration.ofMillis(120), 8L * 1024 * 1024, "wp04-03.test");
        Extraction extraction = new OpenAiCompatibleOcrExtractor(properties, objectMapper)
                .extract(new byte[]{1}, "image/png", "a.png");

        assertThat(extraction.status()).isEqualTo(OcrStatus.TIMEOUT);
        assertThat(extraction.errorCode()).isEqualTo("OCR_TIMEOUT");
    }

    private HttpServer respondWith(AtomicReference<String> body, String content) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        created.createContext("/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = objectMapper.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", content)))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        created.start();
        server = created;
        return created;
    }

    private static OcrProperties properties(String baseUrl) {
        return new OcrProperties(baseUrl, "test-key", "test-model", Duration.ofSeconds(5),
                8L * 1024 * 1024, "wp04-03.test");
    }
}
