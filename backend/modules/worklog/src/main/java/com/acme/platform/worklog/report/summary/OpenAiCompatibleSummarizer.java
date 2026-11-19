package com.acme.platform.worklog.report.summary;

import com.acme.platform.platform.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容的周报摘要适配器（POST /chat/completions）。
 *
 * <p>只发送服务端计算好的事实包，不发送语音原文或完整 prompt 日志。
 * 未配置时抛 503，由上层降级为时长模板，不把占位文字冒充 AI 产出。
 */
public class OpenAiCompatibleSummarizer implements ReportSummarizer {

    private static final String PROVIDER = "openai-compatible";
    private static final int MAX_DESCRIPTION_CHARS = 100;

    private static final String SYSTEM_PROMPT = """
            你是工时周报助手。只能使用用户提供的“已确认工时事实”JSON，不得引入任何外部信息。
            要求：
            1. 只输出 JSON，结构为
               {"summaryPoints":[{"text":"...","entryIds":["..."]}],
                "highlights":[{"text":"...","entryIds":["..."]}],
                "nextWeekPlan":"..."}。
            2. summaryPoints 3-5 条，highlights 最多 3 条；
               每条都必须提供至少一个事实中的 entryId，禁止编造条目。
            3. 不得编造未发生的产出；事实中没有的内容不要写。
            4. 文字中的数字必须直接来自事实 JSON，不得自行推算或夸大。
            5. nextWeekPlan 只能给建议，不得声称已经发生。
            6. 使用简体中文，语气客观，不复述隐私信息。
            """;

    private final ReportAiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleSummarizer(ReportAiProperties properties, ObjectMapper objectMapper) {
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
    public SummaryDraft summarize(WeekFacts facts) {
        if (!configured()) {
            throw new ApiException(503, "REPORT_AI_NOT_CONFIGURED",
                    "周报摘要未配置，已降级为仅汇总时长");
        }
        byte[] body = objectMapper.writeValueAsBytes(requestBody(facts));
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
        } catch (IOException e) {
            throw new ApiException(502, "REPORT_AI_UPSTREAM_FAILED", "周报摘要服务不可达");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "REPORT_AI_UPSTREAM_FAILED", "周报摘要被中断");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException(502, "REPORT_AI_UPSTREAM_FAILED",
                    "周报摘要服务返回 " + response.statusCode());
        }
        String content = extractContent(response.body());
        return parse(content);
    }

    private Map<String, Object> requestBody(WeekFacts facts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("temperature", 0.2);
        body.put("response_format", Map.of("type", "json_object"));

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("weekStart", facts.weekStart().toString());
        user.put("weekEnd", facts.weekEnd().toString());
        user.put("totalMinutes", facts.totalMinutes());
        user.put("confirmedDays", facts.confirmedDays());
        user.put("entries", facts.entries().stream().map(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entryId", entry.entryId().toString());
            row.put("date", entry.date().toString());
            row.put("minutes", entry.minutes());
            row.put("projectId", entry.projectId() == null ? null : entry.projectId().toString());
            row.put("taskId", entry.taskId() == null ? null : entry.taskId().toString());
            row.put("description", shorten(entry.description()));
            return row;
        }).toList());
        user.put("items", facts.items());
        user.put("days", facts.days());
        user.put("anomalies", facts.anomalies());
        String factsJson = objectMapper.writeValueAsString(user);
        String content = "以下是本周已确认工时事实（JSON），请只据此总结：\n" + factsJson;
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", content)));
        return body;
    }

    private static String shorten(String description) {
        if (description == null) {
            return "";
        }
        String text = description.strip();
        return text.length() <= MAX_DESCRIPTION_CHARS
                ? text : text.substring(0, MAX_DESCRIPTION_CHARS) + "…";
    }

    private String extractContent(String responseBody) {
        try {
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
            throw invalidOutput();
        } catch (RuntimeException e) {
            throw invalidOutput();
        }
    }

    private SummaryDraft parse(String raw) {
        String json = stripFences(raw);
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw invalidOutput();
        }
        try {
            JsonNode root = objectMapper.readTree(json.substring(start, end + 1));
            List<SummaryPoint> summaryPoints = points(root.path("summaryPoints"));
            List<SummaryPoint> highlights = points(root.path("highlights"));
            String nextWeekPlan = root.path("nextWeekPlan").isString()
                    ? root.path("nextWeekPlan").asString().strip() : "";
            if (summaryPoints.isEmpty() && highlights.isEmpty() && nextWeekPlan.isEmpty()) {
                throw invalidOutput();
            }
            return new SummaryDraft(summaryPoints, highlights, nextWeekPlan);
        } catch (RuntimeException e) {
            throw invalidOutput();
        }
    }

    private List<SummaryPoint> points(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<SummaryPoint> points = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.path("text").isString() ? item.path("text").asString().strip() : "";
            if (text.isEmpty()) {
                continue;
            }
            List<UUID> entryIds = new ArrayList<>();
            JsonNode ids = item.path("entryIds");
            if (ids.isArray()) {
                for (JsonNode id : ids) {
                    if (id.isString()) {
                        try {
                            entryIds.add(UUID.fromString(id.asString()));
                        } catch (IllegalArgumentException ignored) {
                            // 非法 UUID 由校验器按「无有效来源」丢弃
                        }
                    }
                }
            }
            points.add(new SummaryPoint(text, entryIds));
        }
        return points;
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

    private static ApiException invalidOutput() {
        return new ApiException(422, "REPORT_AI_INVALID_OUTPUT",
                "周报摘要服务返回了无法解析的内容");
    }
}
