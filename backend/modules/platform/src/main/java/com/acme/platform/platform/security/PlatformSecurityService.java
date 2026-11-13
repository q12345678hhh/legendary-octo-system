package com.acme.platform.platform.security;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.audit.PlatformAuditStore;
import com.acme.platform.platform.audit.PlatformOperatorRef;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 安全运营：事件查询、确认、解决、漏洞/事件录入与秘密轮换登记。 */
@Service
public class PlatformSecurityService {

    private static final Pattern CATEGORY = Pattern.compile(
            "^(abnormal_login|cross_tenant_blocked|secret_rotation|vulnerability|incident)$");
    private static final Pattern SEVERITY = Pattern.compile("^(low|medium|high|critical)$");

    private final PlatformSecurityStore store;
    private final PlatformAuditStore auditStore;
    private final ObjectMapper objectMapper;

    public PlatformSecurityService(PlatformSecurityStore store, PlatformAuditStore auditStore,
                                   ObjectMapper objectMapper) {
        this.store = store;
        this.auditStore = auditStore;
        this.objectMapper = objectMapper;
    }

    public List<PlatformSecurityStore.SecurityEvent> list(String category, String status,
                                                          int limit) {
        if (category != null && !category.isBlank() && !CATEGORY.matcher(category).matches()) {
            throw ApiException.badRequest("SECURITY_CATEGORY_INVALID", "未知安全事件类别：" + category);
        }
        return store.list(category, status, limit);
    }

    public PlatformSecurityStore.SecurityEvent acknowledge(UUID id, PlatformOperatorRef operator) {
        if (!store.acknowledge(id, operator.email())) {
            throw ApiException.conflict("SECURITY_EVENT_NOT_OPEN", "事件不存在或已不在待确认状态");
        }
        auditStore.record(operator, "security.event.acknowledge", "platform_security_event",
                id.toString(), Map.of());
        return require(id);
    }

    public PlatformSecurityStore.SecurityEvent resolve(UUID id, PlatformOperatorRef operator) {
        if (!store.resolve(id, operator.email())) {
            throw ApiException.conflict("SECURITY_EVENT_ALREADY_RESOLVED", "事件不存在或已解决");
        }
        auditStore.record(operator, "security.event.resolve", "platform_security_event",
                id.toString(), Map.of());
        return require(id);
    }

    public UUID record(UUID tenantId, String category, String severity, String summary,
                       Map<String, Object> detail, PlatformOperatorRef operator) {
        if (!CATEGORY.matcher(String.valueOf(category)).matches()) {
            throw ApiException.badRequest("SECURITY_CATEGORY_INVALID", "未知安全事件类别：" + category);
        }
        if (!SEVERITY.matcher(String.valueOf(severity)).matches()) {
            throw ApiException.badRequest("SECURITY_SEVERITY_INVALID", "未知安全级别：" + severity);
        }
        String trimmed = summary == null ? "" : summary.trim();
        if (trimmed.length() < 4 || trimmed.length() > 500) {
            throw ApiException.badRequest("SECURITY_SUMMARY_INVALID", "摘要长度必须在 4–500 个字符之间");
        }
        UUID id = store.insert(tenantId, category, severity, trimmed,
                objectMapper.writeValueAsString(detail == null ? Map.of() : detail),
                OffsetDateTime.now());
        auditStore.record(operator, "security.event.record", "platform_security_event",
                id.toString(), Map.of("category", category, "severity", severity));
        return id;
    }

    public UUID recordSecretRotation(String secretName, String note, PlatformOperatorRef operator) {
        String name = secretName == null ? "" : secretName.trim();
        if (name.isEmpty() || name.length() > 64) {
            throw ApiException.badRequest("SECRET_NAME_INVALID", "秘密名称不能为空且不超过 64 个字符");
        }
        String summary = "秘密轮换登记：" + name;
        if (note != null && !note.isBlank()) {
            summary = summary + "（" + note.trim() + "）";
        }
        return record(null, "secret_rotation", "medium", summary,
                Map.of("secretName", name), operator);
    }

    private PlatformSecurityStore.SecurityEvent require(UUID id) {
        return store.find(id).orElseThrow(() ->
                ApiException.notFound("SECURITY_EVENT_NOT_FOUND", "安全事件不存在"));
    }
}
