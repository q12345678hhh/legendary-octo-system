package com.acme.platform.expense.ocr;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 单据 OCR 配置。
 *
 * <p>默认全部为空：未配置时不做任何外部调用，单据直接转人工。
 *
 * @param baseUrl       OpenAI 兼容服务根地址，例如 https://api.openai.com/v1
 * @param apiKey        Bearer 令牌
 * @param model         视觉模型
 * @param timeout       单次调用超时
 * @param maxBytes      允许下载并发送的单据字节上限
 * @param promptVersion 提示词版本，写入抽取记录
 */
@ConfigurationProperties(prefix = "platform.ocr")
public record OcrProperties(
        String baseUrl,
        String apiKey,
        String model,
        Duration timeout,
        Long maxBytes,
        String promptVersion) {

    public OcrProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null || model.isBlank() ? "gpt-4o-mini" : model.strip();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        maxBytes = maxBytes == null || maxBytes < 1024 ? 8L * 1024 * 1024 : maxBytes;
        promptVersion = promptVersion == null || promptVersion.isBlank()
                ? "wp04-03.v1" : promptVersion.strip();
    }

    public boolean configured() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
    }
}
