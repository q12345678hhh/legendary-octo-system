package com.acme.platform.platform.settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 平台设置键白名单。
 *
 * <p>新增键必须同时在数据库 CHECK、本目录与运行手册中登记；未知键直接 422，
 * 避免把平台设置表当成任意配置仓库。
 */
public final class PlatformSettingCatalog {

    public enum Kind { TEXT, INT, URL, EMAIL }

    public record Definition(String key, String category, Kind kind, boolean secret,
                             int min, int max) {
    }

    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();

    static {
        define(new Definition("email.smtp_host", "email", Kind.TEXT, false, 1, 255));
        define(new Definition("email.smtp_port", "email", Kind.INT, false, 1, 65535));
        define(new Definition("email.from_address", "email", Kind.EMAIL, false, 3, 254));
        define(new Definition("email.username", "email", Kind.TEXT, false, 1, 254));
        define(new Definition("email.password", "email", Kind.TEXT, true, 1, 512));
        define(new Definition("storage.endpoint", "storage", Kind.URL, false, 8, 512));
        define(new Definition("storage.bucket", "storage", Kind.TEXT, false, 1, 128));
        define(new Definition("storage.region", "storage", Kind.TEXT, false, 1, 64));
        define(new Definition("storage.access_key", "storage", Kind.TEXT, true, 1, 256));
        define(new Definition("storage.secret_key", "storage", Kind.TEXT, true, 1, 512));
        define(new Definition("model.gateway_url", "model", Kind.URL, false, 8, 512));
        define(new Definition("model.gateway_key", "model", Kind.TEXT, true, 1, 512));
        define(new Definition("limits.max_tenants", "limits", Kind.INT, false, 1, 10000));
        define(new Definition("limits.support_access_max_minutes", "limits", Kind.INT, false, 1, 60));
        // 保留策略：min 即合规下限，低于下限在写入口返回 422 RETENTION_BELOW_MINIMUM。
        define(new Definition("retention.audit_days", "retention", Kind.INT, false, 2555, 36500));
        define(new Definition("retention.business_log_days", "retention", Kind.INT, false, 90, 3650));
        define(new Definition("retention.recycle_bin_days", "retention", Kind.INT, false, 7, 90));
    }

    private static void define(Definition definition) {
        DEFINITIONS.put(definition.key(), definition);
    }

    private PlatformSettingCatalog() {
    }

    public static Optional<Definition> find(String key) {
        return Optional.ofNullable(DEFINITIONS.get(key));
    }

    public static Map<String, Definition> definitions() {
        return Map.copyOf(DEFINITIONS);
    }
}
