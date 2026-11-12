package com.acme.platform.platform.settings;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 平台设置的 JDBC 存储；密钥值只写不读。 */
@Component
public class PlatformSettingStore {

    public record SettingRow(String key, String category, String valueJson, boolean secret,
                             String updatedBy, OffsetDateTime updatedAt) {
    }

    private static final RowMapper<SettingRow> MAPPER = (rs, rowNum) -> new SettingRow(
            rs.getString("setting_key"), rs.getString("category"), rs.getString("value"),
            rs.getBoolean("is_secret"), rs.getString("updated_by"),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public PlatformSettingStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SettingRow> all() {
        return jdbcTemplate.query("""
                SELECT setting_key, category, value, is_secret, updated_by, updated_at
                  FROM platform_setting ORDER BY setting_key
                """, MAPPER);
    }

    public Optional<SettingRow> find(String key) {
        return jdbcTemplate.query("""
                SELECT setting_key, category, value, is_secret, updated_by, updated_at
                  FROM platform_setting WHERE setting_key = ?
                """, MAPPER, key).stream().findFirst();
    }

    public void upsert(String key, String category, String valueJson, boolean secret, String actor) {
        jdbcTemplate.update("""
                INSERT INTO platform_setting (setting_key, category, value, is_secret, updated_by)
                VALUES (?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (setting_key) DO UPDATE
                   SET value = EXCLUDED.value, is_secret = EXCLUDED.is_secret,
                       updated_by = EXCLUDED.updated_by, updated_at = now()
                """, key, category, valueJson, secret, actor);
    }

    public Optional<Integer> intValue(String key) {
        return jdbcTemplate.query("""
                SELECT (value #>> '{}')::int AS v FROM platform_setting WHERE setting_key = ?
                """, (rs, rowNum) -> rs.getInt("v"), key).stream().findFirst();
    }
}
