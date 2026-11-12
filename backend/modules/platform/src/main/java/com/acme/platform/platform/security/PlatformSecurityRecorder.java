package com.acme.platform.platform.security;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 安全事件的「旁路」记录器：供过滤器与登录服务调用。
 *
 * <p>记录失败绝不能影响主请求，因此统一吞掉异常只打日志；明细只允许 ID、路径与计数，
 * 不允许写入密码、令牌、请求体或业务数据。
 */
@Component
public class PlatformSecurityRecorder {

    private static final Logger log = LoggerFactory.getLogger(PlatformSecurityRecorder.class);

    private final PlatformSecurityStore store;
    private final ObjectMapper objectMapper;

    public PlatformSecurityRecorder(PlatformSecurityStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public void abnormalLogin(UUID operatorId, String email, int attempts) {
        safeInsert(null, "abnormal_login", "high",
                "运营账号连续登录失败，已触发临时锁定", Map.of(
                        "operatorId", String.valueOf(operatorId),
                        "email", email == null ? "" : email,
                        "failedAttempts", attempts));
    }

    public void crossTenantBlocked(String path, String code, String ip) {
        safeInsert(null, "cross_tenant_blocked", "medium",
                "平台域与租户域出现越界请求，已被过滤器阻断", Map.of(
                        "path", path == null ? "" : path,
                        "code", code == null ? "" : code,
                        "ip", ip == null ? "" : ip));
    }

    public void tenantHeaderMismatch(UUID tenantId, String code) {
        safeInsert(tenantId, "cross_tenant_blocked", "medium",
                "租户请求头与会话不一致，已拒绝", Map.of(
                        "code", code == null ? "" : code));
    }

    private void safeInsert(UUID tenantId, String category, String severity, String summary,
                            Map<String, Object> detail) {
        try {
            Map<String, Object> copy = new LinkedHashMap<>(detail);
            store.insert(tenantId, category, severity, summary,
                    objectMapper.writeValueAsString(copy), OffsetDateTime.now());
        } catch (RuntimeException failure) {
            log.warn("安全事件记录失败（不影响主请求）：{} {}", category, failure.getMessage());
        }
    }
}
