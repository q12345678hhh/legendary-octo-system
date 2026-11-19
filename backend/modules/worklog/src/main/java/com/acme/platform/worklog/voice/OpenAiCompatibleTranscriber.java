package com.acme.platform.worklog.voice;

import com.acme.platform.platform.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容的语音转写适配器（multipart POST /audio/transcriptions）。
 *
 * <p>只依赖 JDK HttpClient，避免为一次转写引入云厂商 SDK。
 * 失败一律抛 502/422，不返回占位文本。
 */
public class OpenAiCompatibleTranscriber implements SpeechTranscriber {

    private static final String PROVIDER = "openai-compatible";

    private final AsrProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleTranscriber(AsrProperties properties, ObjectMapper objectMapper) {
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
    public Transcription transcribe(byte[] audio, String contentType) {
        if (!configured()) {
            throw new ApiException(503, "ASR_NOT_CONFIGURED",
                    "语音转写未配置，请改用文字录入或联系管理员配置 ASR");
        }
        String boundary = "----platform" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, audio, contentType);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(properties.baseUrl() + "/audio/transcriptions"))
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(502, "ASR_UPSTREAM_FAILED", "语音转写服务不可达");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "ASR_UPSTREAM_FAILED", "语音转写被中断");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException(502, "ASR_UPSTREAM_FAILED",
                    "语音转写服务返回 " + response.statusCode());
        }
        String text = extractText(response.body());
        if (text.isBlank()) {
            throw new ApiException(422, "ASR_EMPTY_TRANSCRIPT",
                    "没有识别到语音内容，请重新录音或改用文字录入");
        }
        return new Transcription(text, PROVIDER, properties.model(), null);
    }

    private String extractText(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            return node.path("text").asString().strip();
        } catch (RuntimeException e) {
            throw new ApiException(502, "ASR_UPSTREAM_INVALID",
                    "语音转写服务返回了无法解析的内容");
        }
    }

    private byte[] multipart(String boundary, byte[] audio, String contentType) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(audio.length + 1024);
            writeField(out, boundary, "model", properties.model());
            if (!properties.language().isEmpty()) {
                writeField(out, boundary, "language", properties.language());
            }
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"voice-upload\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(audio);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("无法组装转写请求体", e);
        }
    }

    private static void writeField(ByteArrayOutputStream out, String boundary, String name,
                                   String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
