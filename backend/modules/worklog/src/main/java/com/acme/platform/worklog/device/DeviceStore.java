package com.acme.platform.worklog.device;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 设备登记读写；所有调用都必须发生在 {@code TenantTransactions.inTenant} 内，才能命中 RLS。 */
@Component
public class DeviceStore {

    private static final String COLUMNS = """
            id, tenant_id, subject_id, device_name, public_key_spki, app_version,
            policy_version, status, registered_at, last_seen_at, revoked_at, revoked_by
            """;

    private final JdbcTemplate jdbcTemplate;

    public DeviceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DeviceRecord> find(UUID id) {
        List<DeviceRecord> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM work_assistant_device WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<DeviceRecord> find(String subjectId, String publicKeySpki) {
        List<DeviceRecord> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM work_assistant_device"
                        + " WHERE subject_id = ? AND public_key_spki = ?",
                MAPPER, subjectId, publicKeySpki);
        return rows.stream().findFirst();
    }

    public DeviceRecord insert(UUID id, UUID tenantId, String subjectId, String deviceName,
                               String publicKeySpki, String appVersion) {
        jdbcTemplate.update("""
                INSERT INTO work_assistant_device
                    (id, tenant_id, subject_id, device_name, public_key_spki, app_version)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, tenantId, subjectId, deviceName, publicKeySpki, appVersion);
        return find(id).orElseThrow(() -> new IllegalStateException("设备写入后不可见: " + id));
    }

    public void touch(UUID id, String appVersion, int policyVersion) {
        jdbcTemplate.update("""
                UPDATE work_assistant_device
                   SET last_seen_at = now(), app_version = ?, policy_version = ?
                 WHERE id = ?
                """, appVersion, policyVersion, id);
    }

    public boolean revoke(UUID id, String revokedBy) {
        return jdbcTemplate.update("""
                UPDATE work_assistant_device
                   SET status = 'revoked', revoked_at = now(), revoked_by = ?
                 WHERE id = ? AND status = 'active'
                """, revokedBy, id) > 0;
    }

    private static final RowMapper<DeviceRecord> MAPPER = (ResultSet rs, int rowNum) -> map(rs);

    private static DeviceRecord map(ResultSet rs) throws SQLException {
        return new DeviceRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("subject_id"),
                rs.getString("device_name"),
                rs.getString("public_key_spki"),
                rs.getString("app_version"),
                rs.getInt("policy_version"),
                rs.getString("status"),
                rs.getObject("registered_at", java.time.OffsetDateTime.class),
                rs.getObject("last_seen_at", java.time.OffsetDateTime.class),
                rs.getObject("revoked_at", java.time.OffsetDateTime.class),
                rs.getString("revoked_by"));
    }
}
