package com.acme.platform.tenancy.privacy;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 租户隐私设置的 JDBC 存储；调用方必须处于租户事务内。 */
@Component
public class PrivacySettingsStore {

    private static final String COLUMNS = """
            tenant_id, data_residency, training_opt_out,
            voice_transcript_retention_days, expense_ocr_retention_days,
            updated_by, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public PrivacySettingsStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PrivacySetting> find(UUID tenantId) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM tenant_privacy_setting WHERE tenant_id = ?", PrivacySettingsStore::map,
                tenantId).stream().findFirst();
    }

    public PrivacySetting upsert(UUID tenantId, String dataResidency, boolean trainingOptOut,
                                 int voiceDays, int ocrDays, String updatedBy) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tenant_privacy_setting
                    (tenant_id, data_residency, training_opt_out,
                     voice_transcript_retention_days, expense_ocr_retention_days, updated_by)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE
                   SET data_residency = EXCLUDED.data_residency,
                       training_opt_out = EXCLUDED.training_opt_out,
                       voice_transcript_retention_days =
                           EXCLUDED.voice_transcript_retention_days,
                       expense_ocr_retention_days = EXCLUDED.expense_ocr_retention_days,
                       updated_by = EXCLUDED.updated_by,
                       updated_at = now()
                RETURNING %s
                """.formatted(COLUMNS), PrivacySettingsStore::map,
                tenantId, dataResidency, trainingOptOut, voiceDays, ocrDays, updatedBy);
    }

    private static PrivacySetting map(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new PrivacySetting(
                rs.getObject("tenant_id", UUID.class),
                rs.getString("data_residency"),
                rs.getBoolean("training_opt_out"),
                rs.getInt("voice_transcript_retention_days"),
                rs.getInt("expense_ocr_retention_days"),
                rs.getString("updated_by"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
