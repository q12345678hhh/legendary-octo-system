package com.acme.platform.tenancy.retention;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 法定留置的 JDBC 存储。
 */
@Component
public class LegalHoldStore {

    private static final RowMapper<LegalHold> MAPPER = (rs, rowNum) -> new LegalHold(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("data_class"),
            rs.getObject("object_id", UUID.class),
            rs.getString("reason"),
            rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public LegalHoldStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 对象级优先，其次整类留置。 */
    public Optional<LegalHold> findActive(UUID tenantId, String dataClass, UUID objectId) {
        List<LegalHold> hits = jdbcTemplate.query("""
                        SELECT * FROM retention_legal_hold
                        WHERE tenant_id = ? AND data_class = ? AND released_at IS NULL
                          AND (object_id = ? OR object_id IS NULL)
                        ORDER BY object_id NULLS LAST
                        LIMIT 1
                        """, MAPPER, tenantId, dataClass, objectId);
        return hits.stream().findFirst();
    }

    public List<LegalHold> listActive(UUID tenantId) {
        return jdbcTemplate.query("""
                        SELECT * FROM retention_legal_hold
                        WHERE tenant_id = ? AND released_at IS NULL
                        ORDER BY created_at DESC
                        """, MAPPER, tenantId);
    }
}
