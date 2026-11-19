package com.acme.platform.worklog.report.summary;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 周报摘要配置。
 *
 * <p>默认全部为空：未配置时不做任何模型调用，直接降级为「仅汇总时长」模板。
 *
 * @param baseUrl      OpenAI 兼容服务根地址，例如 https://api.openai.com/v1
 * @param apiKey       Bearer 令牌
 * @param model        摘要模型
 * @param timeout      单次调用超时
 * @param maxInputChars 发送给模型的事实文本上限
 * @param promptVersion 提示词版本，写入周报生成元数据
 */
@ConfigurationProperties(prefix = "platform.ai.report")
public record ReportAiProperties(
        String baseUrl,
        String apiKey,
        String model,
        Duration timeout,
        Integer maxInputChars,
        String promptVersion) {

    public ReportAiProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null || model.isBlank() ? "gpt-4o-mini" : model.strip();
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        maxInputChars = maxInputChars == null || maxInputChars < 500 ? 8000 : maxInputChars;
        promptVersion = promptVersion == null || promptVersion.isBlank()
                ? "wp04-02.v1" : promptVersion.strip();
    }

    public boolean configured() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
    }
}
