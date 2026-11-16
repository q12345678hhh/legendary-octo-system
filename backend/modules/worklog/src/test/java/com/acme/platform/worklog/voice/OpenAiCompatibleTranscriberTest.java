package com.acme.platform.worklog.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.platform.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiCompatibleTranscriberTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void unconfiguredNeverCallsProviderAndReturns503() {
        OpenAiCompatibleTranscriber transcriber = new OpenAiCompatibleTranscriber(
                new AsrProperties("", "", null, null, null, null), new ObjectMapper());

        assertThat(transcriber.configured()).isFalse();
        assertThatThrownBy(() -> transcriber.transcribe(new byte[] {1}, "audio/webm"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.code()).isEqualTo("ASR_NOT_CONFIGURED");
                });
    }

    @Test
    void parsesTranscriptAndSendsAudioAsMultipart() throws IOException {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio/transcriptions", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "{\"text\":\"  整理客户方案  \"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenAiCompatibleTranscriber transcriber = new OpenAiCompatibleTranscriber(
                properties("http://127.0.0.1:" + server.getAddress().getPort()),
                new ObjectMapper());
        SpeechTranscriber.Transcription result = transcriber.transcribe(
                "audio-bytes".getBytes(StandardCharsets.UTF_8), "audio/webm");

        assertThat(result.text()).isEqualTo("整理客户方案");
        assertThat(result.provider()).isEqualTo("openai-compatible");
        assertThat(result.model()).isEqualTo("whisper-1");
        assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
        assertThat(new String(body.get(), StandardCharsets.UTF_8)).contains("audio-bytes");
    }

    @Test
    void upstreamFailureMapsTo502() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio/transcriptions", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        OpenAiCompatibleTranscriber transcriber = new OpenAiCompatibleTranscriber(
                properties("http://127.0.0.1:" + server.getAddress().getPort()),
                new ObjectMapper());

        assertThatThrownBy(() -> transcriber.transcribe(new byte[] {1}, "audio/webm"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(502);
                    assertThat(e.code()).isEqualTo("ASR_UPSTREAM_FAILED");
                });
    }

    private static AsrProperties properties(String baseUrl) {
        return new AsrProperties(baseUrl, "test-key", "whisper-1", "zh", Duration.ofSeconds(5),
                1024L);
    }
}
