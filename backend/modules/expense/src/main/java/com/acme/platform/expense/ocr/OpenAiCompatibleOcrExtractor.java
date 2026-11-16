package com.acme.platform.expense.ocr;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容的票据抽取适配器（POST /chat/completions）。
 *
 * <p>只发送单据图片本身，不发送任何员工或部门上下文；OCR 原文只随结果返回，
 * 由上层写入不可变表，不在此记录日志。PDF 不做文本抽取，直接返回
 * {@link OcrStatus#PDF_UNSUPPORTED}，避免用不可靠的文本层冒充 OCR。
 */
public class OpenAiCompatibleOcrExtractor implements DocumentExtractor {

    private static final String PROVIDER = "openai-compatible";
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp");
    private static final int MAX_OCR_CHARS = 20000;

    private static final String SYSTEM_PROMPT = """
            你是票据字段抽取器。只读取图片中真实可见的内容，不得推测或补全。
            只输出 JSON，结构为：
            {"amount":数字,"currency":"CNY","invoiceCode":"...","invoiceNo":"...",
             "vendorName":"...","vendorTaxNo":"...","occurredOn":"YYYY-MM-DD",
             "category":"...","title":"...","lines":[{"description":"...","amount":数字}],
             "ocrText":"完整可见文字","confidence":{"amount":0-1,"currency":0-1,
             "invoiceNo":0-1,"vendorName":0-1,"occurredOn":0-1,"title":0-1}}
            规则：
            1. 看不清的字段置 null，并在 confidence 中给低分；
            2. 金额必须是票面合计，币种用 ISO 代码；
            3. 不得编造发票号、税号或供应商；
            4. 不要输出 JSON 之外的解释。
            """;

    private final OcrProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleOcrExtractor(OcrProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public boolean configured() {
        return properties.configured();
    }

    @Override
    public Extraction extract(byte[] content, String contentType, String fileName) {
        long started = System.nanoTime();
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].strip();
        if ("application/pdf".equals(type)) {
            return Extraction.failure(OcrStatus.PDF_UNSUPPORTED, "PDF_UNSUPPORTED", elapsed(started));
        }
        if (!IMAGE_TYPES.contains(type)) {
            return Extraction.failure(OcrStatus.INVALID, "UNSUPPORTED_CONTENT_TYPE", elapsed(started));
        }
        if (!configured()) {
            return Extraction.failure(OcrStatus.UNAVAILABLE, "OCR_NOT_CONFIGURED", elapsed(started));
        }
        if (content == null || content.length == 0) {
            return Extraction.failure(OcrStatus.INVALID, "EMPTY_CONTENT", elapsed(started));
        }

        byte[] body = objectMapper.writeValueAsBytes(requestBody(content, type, fileName));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(properties.baseUrl() + "/chat/completions"))
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            return Extraction.failure(OcrStatus.TIMEOUT, "OCR_TIMEOUT", elapsed(started));
        } catch (IOException e) {
            return Extraction.failure(OcrStatus.UNAVAILABLE, "OCR_UNREACHABLE", elapsed(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Extraction.failure(OcrStatus.UNAVAILABLE, "OCR_INTERRUPTED", elapsed(started));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return Extraction.failure(OcrStatus.UNAVAILABLE,
                    "OCR_HTTP_" + response.statusCode(), elapsed(started));
        }
        String raw;
        try {
            raw = extractContent(response.body());
        } catch (RuntimeException e) {
            return Extraction.failure(OcrStatus.INVALID, "OCR_OUTPUT_INVALID", elapsed(started));
        }
        return parse(raw, elapsed(started));
    }

    private Map<String, Object> requestBody(byte[] content, String contentType, String fileName) {
        String dataUrl = "data:" + contentType + ";base64,"
                + Base64.getEncoder().encodeToString(content);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));
        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text",
                "text", "请抽取这张票据的字段，文件名：" + (fileName == null ? "" : fileName)));
        userContent.add(Map.of("type", "image_url",
                "image_url", Map.of("url", dataUrl)));
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userContent)));
        return body;
    }

    private String extractContent(String responseBody) {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isString()) {
            return content.asString();
        }
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : content) {
                JsonNode text = part.path("text");
                if (text.isString()) {
                    builder.append(text.asString());
                }
            }
            return builder.toString();
        }
        throw new IllegalStateException("缺少 content");
    }

    private Extraction parse(String raw, long latencyMs) {
        String json = stripFences(raw);
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Extraction.failure(OcrStatus.INVALID, "OCR_OUTPUT_INVALID", latencyMs);
        }
        try {
            JsonNode root = objectMapper.readTree(json.substring(start, end + 1));
            ExtractedFields fields = new ExtractedFields(
                    decimal(root.path("amount")),
                    text(root.path("currency")),
                    text(root.path("invoiceCode")),
                    text(root.path("invoiceNo")),
                    text(root.path("vendorName")),
                    text(root.path("vendorTaxNo")),
                    date(root.path("occurredOn")),
                    text(root.path("category")),
                    text(root.path("title")),
                    lines(root.path("lines")));
            Map<String, Double> confidence = confidence(root.path("confidence"));
            String ocrText = text(root.path("ocrText"));
            if (ocrText != null && ocrText.length() > MAX_OCR_CHARS) {
                ocrText = ocrText.substring(0, MAX_OCR_CHARS);
            }
            if (fields.amount() == null && fields.invoiceNo() == null && ocrText == null) {
                return Extraction.failure(OcrStatus.INVALID, "OCR_OUTPUT_EMPTY", latencyMs);
            }
            return new Extraction(OcrStatus.OK, fields, confidence, ocrText,
                    PROVIDER, properties.model(), latencyMs, null);
        } catch (RuntimeException e) {
            return Extraction.failure(OcrStatus.INVALID, "OCR_OUTPUT_INVALID", latencyMs);
        }
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isString()) {
            String value = node.asString().strip().replace(",", "");
            if (!value.isEmpty()) {
                try {
                    return new BigDecimal(value);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }

    private static LocalDate date(JsonNode node) {
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<ExtractedFields.LineItem> lines(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<ExtractedFields.LineItem> rows = new ArrayList<>();
        for (JsonNode item : node) {
            BigDecimal amount = decimal(item.path("amount"));
            String description = text(item.path("description"));
            if (amount == null && description == null) {
                continue;
            }
            rows.add(new ExtractedFields.LineItem(description, amount));
        }
        return rows;
    }

    private static Map<String, Double> confidence(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (String field : node.propertyNames()) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                values.put(field, Math.max(0, Math.min(1, value.asDouble())));
            }
        }
        return values;
    }

    private static String stripFences(String value) {
        String text = value == null ? "" : value.strip();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            if (firstLine > 0) {
                text = text.substring(firstLine + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.strip();
    }

    private static long elapsed(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
