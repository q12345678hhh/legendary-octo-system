package com.acme.platform.platform.settings;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.audit.PlatformAuditStore;
import com.acme.platform.platform.audit.PlatformOperatorRef;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 平台设置：白名单校验、密钥脱敏与变更审计。 */
@Service
public class PlatformSettingService {

    public record SettingView(String key, String category, JsonNode value, boolean secret,
                              boolean configured, String updatedBy, OffsetDateTime updatedAt) {
    }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final PlatformSettingStore store;
    private final PlatformAuditStore auditStore;
    private final ObjectMapper objectMapper;

    public PlatformSettingService(PlatformSettingStore store, PlatformAuditStore auditStore,
                                  ObjectMapper objectMapper) {
        this.store = store;
        this.auditStore = auditStore;
        this.objectMapper = objectMapper;
    }

    public List<SettingView> all() {
        List<SettingView> views = new ArrayList<>();
        for (PlatformSettingCatalog.Definition definition
                : PlatformSettingCatalog.definitions().values()) {
            Optional<PlatformSettingStore.SettingRow> row = store.find(definition.key());
            if (row.isEmpty()) {
                views.add(new SettingView(definition.key(), definition.category(), null,
                        definition.secret(), false, null, null));
                continue;
            }
            PlatformSettingStore.SettingRow value = row.get();
            boolean configured = value.valueJson() != null && !value.valueJson().isBlank()
                    && !"null".equals(value.valueJson()) && !"\"\"".equals(value.valueJson());
            JsonNode json = definition.secret() ? null
                    : (configured ? objectMapper.readTree(value.valueJson()) : null);
            views.add(new SettingView(definition.key(), definition.category(), json,
                    definition.secret(), configured, value.updatedBy(), value.updatedAt()));
        }
        return views;
    }

    public SettingView put(String key, JsonNode value, PlatformOperatorRef operator) {
        PlatformSettingCatalog.Definition definition = PlatformSettingCatalog.find(key)
                .orElseThrow(() -> ApiException.unprocessable("SETTING_UNKNOWN_KEY",
                        "未知的平台设置键：" + key));
        String json = validate(definition, value);
        store.upsert(definition.key(), definition.category(), json, definition.secret(),
                operator.email());
        auditStore.record(operator, "settings.update", "platform_setting", definition.key(),
                Map.of("secret", definition.secret()));
        return all().stream().filter(view -> view.key().equals(key)).findFirst().orElseThrow();
    }

    public int intLimit(String key, int fallback) {
        PlatformSettingCatalog.Definition definition = PlatformSettingCatalog.find(key)
                .orElseThrow(() -> new IllegalArgumentException("未登记的限制键：" + key));
        return store.intValue(key).filter(value ->
                value >= definition.min() && value <= definition.max()).orElse(fallback);
    }

    private String validate(PlatformSettingCatalog.Definition definition, JsonNode value) {
        if (value == null || value.isNull()) {
            throw ApiException.badRequest("SETTING_VALUE_REQUIRED", "设置值不能为空");
        }
        switch (definition.kind()) {
            case INT -> {
                if (!value.canConvertToInt()) {
                    throw ApiException.badRequest("SETTING_VALUE_INVALID", "该设置必须是整数");
                }
                int number = value.asInt();
                if (number < definition.min() || number > definition.max()) {
                    throw ApiException.badRequest("SETTING_VALUE_OUT_OF_RANGE",
                            "取值必须在 " + definition.min() + "–" + definition.max() + " 之间");
                }
                return Integer.toString(number);
            }
            case EMAIL -> {
                String text = text(value);
                if (!EMAIL.matcher(text).matches()) {
                    throw ApiException.badRequest("SETTING_VALUE_INVALID", "邮箱格式不正确");
                }
                return objectMapper.writeValueAsString(text);
            }
            case URL -> {
                String text = text(value);
                if (!(text.startsWith("https://") || text.startsWith("http://"))) {
                    throw ApiException.badRequest("SETTING_VALUE_INVALID", "地址必须以 http:// 或 https:// 开头");
                }
                return objectMapper.writeValueAsString(text);
            }
            default -> {
                return objectMapper.writeValueAsString(text(value));
            }
        }
    }

    private String text(JsonNode value) {
        if (!value.isString()) {
            throw ApiException.badRequest("SETTING_VALUE_INVALID", "该设置必须是字符串");
        }
        String text = value.asString().trim();
        if (text.isEmpty()) {
            throw ApiException.badRequest("SETTING_VALUE_REQUIRED", "设置值不能为空");
        }
        return text;
    }
}
