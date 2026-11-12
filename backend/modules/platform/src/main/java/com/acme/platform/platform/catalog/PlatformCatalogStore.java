package com.acme.platform.platform.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 平台目录存储：内置应用版本、连接器、插件签名、模型供应商。 */
@Component
public class PlatformCatalogStore {

    public record AppVersion(UUID id, String appCode, String appName, String version, String status,
                             OffsetDateTime releasedAt, String notes) {
    }

    public record Connector(UUID id, String connectorCode, String name, String authType,
                            String status, OffsetDateTime updatedAt) {
    }

    public record PluginSignature(UUID id, String pluginCode, String version, String signer,
                                  String keyId, String sha256, String status,
                                  OffsetDateTime signedAt) {
    }

    public record ModelProvider(UUID id, String providerCode, String name, String gatewayRoute,
                                String status, OffsetDateTime updatedAt) {
    }

    private static final RowMapper<AppVersion> APP_MAPPER = (rs, i) -> new AppVersion(
            rs.getObject("id", UUID.class), rs.getString("app_code"), rs.getString("app_name"),
            rs.getString("version"), rs.getString("status"),
            rs.getObject("released_at", OffsetDateTime.class), rs.getString("notes"));

    private static final RowMapper<Connector> CONNECTOR_MAPPER = (rs, i) -> new Connector(
            rs.getObject("id", UUID.class), rs.getString("connector_code"), rs.getString("name"),
            rs.getString("auth_type"), rs.getString("status"),
            rs.getObject("updated_at", OffsetDateTime.class));

    private static final RowMapper<PluginSignature> SIGNATURE_MAPPER = (rs, i) -> new PluginSignature(
            rs.getObject("id", UUID.class), rs.getString("plugin_code"), rs.getString("version"),
            rs.getString("signer"), rs.getString("key_id"), rs.getString("sha256"),
            rs.getString("status"), rs.getObject("signed_at", OffsetDateTime.class));

    private static final RowMapper<ModelProvider> PROVIDER_MAPPER = (rs, i) -> new ModelProvider(
            rs.getObject("id", UUID.class), rs.getString("provider_code"), rs.getString("name"),
            rs.getString("gateway_route"), rs.getString("status"),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public PlatformCatalogStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AppVersion> apps() {
        return jdbcTemplate.query("""
                SELECT id, app_code, app_name, version, status, released_at, notes
                  FROM platform_app_version ORDER BY app_code, released_at DESC
                """, APP_MAPPER);
    }

    public void upsertApp(String appCode, String appName, String version, String status,
                          String notes) {
        jdbcTemplate.update("""
                INSERT INTO platform_app_version (app_code, app_name, version, status, notes)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (app_code, version) DO UPDATE
                   SET app_name = EXCLUDED.app_name, status = EXCLUDED.status,
                       notes = EXCLUDED.notes
                """, appCode, appName, version, status, notes);
    }

    public List<Connector> connectors() {
        return jdbcTemplate.query("""
                SELECT id, connector_code, name, auth_type, status, updated_at
                  FROM platform_connector ORDER BY connector_code
                """, CONNECTOR_MAPPER);
    }

    public void upsertConnector(String connectorCode, String name, String authType, String status) {
        jdbcTemplate.update("""
                INSERT INTO platform_connector (connector_code, name, auth_type, status)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (connector_code) DO UPDATE
                   SET name = EXCLUDED.name, auth_type = EXCLUDED.auth_type,
                       status = EXCLUDED.status, updated_at = now()
                """, connectorCode, name, authType, status);
    }

    public List<PluginSignature> pluginSignatures() {
        return jdbcTemplate.query("""
                SELECT id, plugin_code, version, signer, key_id, sha256, status, signed_at
                  FROM platform_plugin_signature ORDER BY plugin_code, signed_at DESC
                """, SIGNATURE_MAPPER);
    }

    public void upsertPluginSignature(String pluginCode, String version, String signer,
                                      String keyId, String sha256, String status) {
        jdbcTemplate.update("""
                INSERT INTO platform_plugin_signature
                    (plugin_code, version, signer, key_id, sha256, status)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (plugin_code, version) DO UPDATE
                   SET signer = EXCLUDED.signer, key_id = EXCLUDED.key_id,
                       sha256 = EXCLUDED.sha256, status = EXCLUDED.status
                """, pluginCode, version, signer, keyId, sha256, status);
    }

    public List<ModelProvider> modelProviders() {
        return jdbcTemplate.query("""
                SELECT id, provider_code, name, gateway_route, status, updated_at
                  FROM platform_model_provider ORDER BY provider_code
                """, PROVIDER_MAPPER);
    }

    public void upsertModelProvider(String providerCode, String name, String gatewayRoute,
                                    String status) {
        jdbcTemplate.update("""
                INSERT INTO platform_model_provider (provider_code, name, gateway_route, status)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (provider_code) DO UPDATE
                   SET name = EXCLUDED.name, gateway_route = EXCLUDED.gateway_route,
                       status = EXCLUDED.status, updated_at = now()
                """, providerCode, name, gatewayRoute, status);
    }
}
