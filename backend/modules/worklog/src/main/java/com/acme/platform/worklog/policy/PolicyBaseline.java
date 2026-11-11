package com.acme.platform.worklog.policy;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 平台强制排除下限。
 *
 * <p>唯一事实源是 {@code contracts/work-assistant/baseline-policy.v1.json}，
 * 模块内资源文件是它的逐字副本；`tests/contracts/baseline-policy.test.mjs` 会校验两者一致。
 * 服务端只做「基线 ∪ 租户追加项」，没有任何入口能删减基线。
 */
@Component
public class PolicyBaseline {

    private static final String RESOURCE = "work-assistant/baseline-policy.v1.json";

    private final List<String> processes;
    private final List<String> titleKeywords;

    public PolicyBaseline(ObjectMapper objectMapper) {
        try (InputStream stream = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(stream.readAllBytes());
            this.processes = texts(root.path("processes"));
            this.titleKeywords = texts(root.path("title_keywords"));
        } catch (IOException e) {
            throw new IllegalStateException("缺少强制排除基线资源: " + RESOURCE, e);
        }
        if (processes.isEmpty() || titleKeywords.isEmpty()) {
            throw new IllegalStateException("强制排除基线不能为空: " + RESOURCE);
        }
    }

    public List<String> processes() {
        return processes;
    }

    public List<String> titleKeywords() {
        return titleKeywords;
    }

    private static List<String> texts(JsonNode array) {
        return array.isArray()
                ? java.util.stream.StreamSupport.stream(array.spliterator(), false)
                        .map(JsonNode::asString)
                        .filter(value -> value != null && !value.isBlank())
                        .toList()
                : List.of();
    }
}
