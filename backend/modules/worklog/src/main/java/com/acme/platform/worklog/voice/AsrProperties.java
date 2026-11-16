package com.acme.platform.worklog.voice;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语音转写配置。
 *
 * <p>默认全部为空：未配置时转写接口返回 503，不做任何「假装识别」的兜底，
 * 避免把占位文本当成员工的原话写进工时。
 *
 * @param baseUrl       OpenAI 兼容服务根地址，例如 https://api.openai.com/v1
 * @param apiKey        Bearer 令牌
 * @param model         转写模型，默认 whisper-1
 * @param language      语言提示，可空
 * @param timeout       单次转写超时
 * @param maxAudioBytes 允许下载的音频上限
 */
@ConfigurationProperties(prefix = "platform.asr")
public record AsrProperties(
        String baseUrl,
        String apiKey,
        String model,
        String language,
        Duration timeout,
        Long maxAudioBytes) {

    public AsrProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null || model.isBlank() ? "whisper-1" : model.strip();
        language = language == null ? "" : language.strip();
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        maxAudioBytes = maxAudioBytes == null ? 10L * 1024 * 1024 : maxAudioBytes;
    }

    public boolean configured() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty();
    }
}
