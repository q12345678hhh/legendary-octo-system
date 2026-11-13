package com.acme.platform.platform.catalog;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.audit.PlatformAuditStore;
import com.acme.platform.platform.audit.PlatformOperatorRef;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 平台目录维护：只做格式校验、写入与审计，不接触任何租户数据。 */
@Service
public class PlatformCatalogService {

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9._-]{1,63}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z._-]{1,32}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public record AppInput(String appCode, String appName, String version, String status,
                           String notes) {
    }

    public record ConnectorInput(String connectorCode, String name, String authType,
                                 String status) {
    }

    public record SignatureInput(String pluginCode, String version, String signer, String keyId,
                                 String sha256, String status) {
    }

    public record ProviderInput(String providerCode, String name, String gatewayRoute,
                                String status) {
    }

    private final PlatformCatalogStore store;
    private final PlatformAuditStore auditStore;

    public PlatformCatalogService(PlatformCatalogStore store, PlatformAuditStore auditStore) {
        this.store = store;
        this.auditStore = auditStore;
    }

    public List<PlatformCatalogStore.AppVersion> apps() {
        return store.apps();
    }

    public void upsertApp(AppInput input, PlatformOperatorRef operator) {
        requireCode(input.appCode(), "APP_CODE_INVALID");
        requireText(input.appName(), "APP_NAME_INVALID", 100);
        requireVersion(input.version());
        String status = allowed(input.status(), "APP_STATUS_INVALID",
                "draft", "published", "retired");
        store.upsertApp(input.appCode(), input.appName().trim(), input.version(), status,
                input.notes());
        auditStore.record(operator, "catalog.app.upsert", "platform_app_version",
                input.appCode() + ":" + input.version(), Map.of("status", status));
    }

    public List<PlatformCatalogStore.Connector> connectors() {
        return store.connectors();
    }

    public void upsertConnector(ConnectorInput input, PlatformOperatorRef operator) {
        requireCode(input.connectorCode(), "CONNECTOR_CODE_INVALID");
        requireText(input.name(), "CONNECTOR_NAME_INVALID", 100);
        String authType = allowed(input.authType(), "CONNECTOR_AUTH_INVALID",
                "none", "api-key", "oauth2", "hmac", "basic");
        String status = allowed(input.status(), "CONNECTOR_STATUS_INVALID",
                "available", "disabled");
        store.upsertConnector(input.connectorCode(), input.name().trim(), authType, status);
        auditStore.record(operator, "catalog.connector.upsert", "platform_connector",
                input.connectorCode(), Map.of("status", status, "authType", authType));
    }

    public List<PlatformCatalogStore.PluginSignature> pluginSignatures() {
        return store.pluginSignatures();
    }

    public void upsertPluginSignature(SignatureInput input, PlatformOperatorRef operator) {
        requireCode(input.pluginCode(), "PLUGIN_CODE_INVALID");
        requireVersion(input.version());
        requireText(input.signer(), "PLUGIN_SIGNER_INVALID", 100);
        requireText(input.keyId(), "PLUGIN_KEY_ID_INVALID", 100);
        if (input.sha256() == null || !SHA256.matcher(input.sha256()).matches()) {
            throw ApiException.badRequest("PLUGIN_SHA256_INVALID", "签名指纹必须是 64 位小写十六进制");
        }
        String status = allowed(input.status(), "PLUGIN_STATUS_INVALID", "trusted", "revoked");
        store.upsertPluginSignature(input.pluginCode(), input.version(), input.signer().trim(),
                input.keyId().trim(), input.sha256(), status);
        auditStore.record(operator, "catalog.plugin.upsert", "platform_plugin_signature",
                input.pluginCode() + ":" + input.version(), Map.of("status", status));
    }

    public List<PlatformCatalogStore.ModelProvider> modelProviders() {
        return store.modelProviders();
    }

    public void upsertModelProvider(ProviderInput input, PlatformOperatorRef operator) {
        requireCode(input.providerCode(), "PROVIDER_CODE_INVALID");
        requireText(input.name(), "PROVIDER_NAME_INVALID", 100);
        requireText(input.gatewayRoute(), "PROVIDER_ROUTE_INVALID", 200);
        String status = allowed(input.status(), "PROVIDER_STATUS_INVALID", "enabled", "disabled");
        store.upsertModelProvider(input.providerCode(), input.name().trim(),
                input.gatewayRoute().trim(), status);
        auditStore.record(operator, "catalog.provider.upsert", "platform_model_provider",
                input.providerCode(), Map.of("status", status));
    }

    private void requireCode(String value, String code) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw ApiException.badRequest(code, "编码必须是小写字母开头，可含数字、点、下划线与连字符，长度 2–64");
        }
    }

    private void requireVersion(String value) {
        if (value == null || !VERSION.matcher(value).matches()) {
            throw ApiException.badRequest("VERSION_INVALID", "版本号格式不合法");
        }
    }

    private void requireText(String value, String code, int max) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > max) {
            throw ApiException.badRequest(code, "字段不能为空且长度不能超过 " + max);
        }
    }

    private String allowed(String value, String code, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) {
                return value;
            }
        }
        throw ApiException.badRequest(code, "取值必须是 " + String.join(" / ", candidates));
    }
}
