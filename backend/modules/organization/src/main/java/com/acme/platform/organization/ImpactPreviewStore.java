package com.acme.platform.organization;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 部门移动影响预览票据的存储。 */
@Component
public class ImpactPreviewStore {

    private static final RowMapper<ImpactPreview> MAPPER = (rs, rowNum) -> new ImpactPreview(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("unit_id", UUID.class),
            rs.getObject("target_parent_id", UUID.class),
            rs.getInt("descendant_count"),
            rs.getInt("employment_count"),
            rs.getInt("position_count"),
            rs.getInt("reporting_line_count"),
            rs.getInt("delegation_count"),
            rs.getInt("scheduled_change_count"),
            rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("consumed_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public ImpactPreviewStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID id, UUID unitId, UUID targetParentId, int descendantCount,
                       int employmentCount, int positionCount, int reportingLineCount,
                       int delegationCount, int scheduledChangeCount, String createdBy,
                       OffsetDateTime expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO organization_impact_preview
                    (id, tenant_id, unit_id, target_parent_id, descendant_count, employment_count,
                     position_count, reporting_line_count, delegation_count,
                     scheduled_change_count, created_by, expires_at)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?)
                """, id, unitId, targetParentId, descendantCount, employmentCount, positionCount,
                reportingLineCount, delegationCount, scheduledChangeCount, createdBy, expiresAt);
    }

    public Optional<ImpactPreview> find(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, unit_id, target_parent_id, descendant_count, employment_count,
                       position_count, reporting_line_count, delegation_count,
                       scheduled_change_count, created_by, created_at, expires_at, consumed_at
                  FROM organization_impact_preview WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    /** 单次消费：只有未消费的票据能改成已消费，并发下第二个请求更新 0 行。 */
    public int consume(UUID id) {
        return jdbcTemplate.update("""
                UPDATE organization_impact_preview SET consumed_at = now()
                 WHERE id = ? AND consumed_at IS NULL
                """, id);
    }
}
