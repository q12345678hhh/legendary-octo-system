package com.acme.platform.organization;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 管理委派存储。 */
@Component
public class DelegationStore {

    private static final RowMapper<Delegation> MAPPER = (rs, rowNum) -> new Delegation(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("delegator_employment_id", UUID.class),
            rs.getObject("delegate_employment_id", UUID.class),
            rs.getObject("scope_org_unit_id", UUID.class),
            rs.getString("reason"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getString("created_by"),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public DelegationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Delegation> find(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, delegator_employment_id, delegate_employment_id,
                       scope_org_unit_id, reason, effective_from, effective_to,
                       created_by, created_at
                  FROM organization_delegation WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public List<Delegation> list() {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, delegator_employment_id, delegate_employment_id,
                       scope_org_unit_id, reason, effective_from, effective_to,
                       created_by, created_at
                  FROM organization_delegation ORDER BY effective_from DESC
                """, MAPPER);
    }

    public List<Delegation> listCurrent(LocalDate today) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, delegator_employment_id, delegate_employment_id,
                       scope_org_unit_id, reason, effective_from, effective_to,
                       created_by, created_at
                  FROM organization_delegation
                 WHERE effective_from <= ? AND effective_to > ?
                 ORDER BY effective_from DESC
                """, MAPPER, today, today);
    }

    public void insert(UUID id, UUID delegatorEmploymentId, UUID delegateEmploymentId,
                       UUID scopeOrgUnitId, String reason, LocalDate from, LocalDate to,
                       String actor) {
        jdbcTemplate.update("""
                INSERT INTO organization_delegation
                    (id, tenant_id, delegator_employment_id, delegate_employment_id,
                     scope_org_unit_id, reason, effective_from, effective_to, created_by)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?)
                """, id, delegatorEmploymentId, delegateEmploymentId, scopeOrgUnitId, reason,
                from, to, actor);
    }

    public int end(UUID id, LocalDate effectiveTo) {
        return jdbcTemplate.update("""
                UPDATE organization_delegation
                   SET effective_to = ?, updated_at = now()
                 WHERE id = ? AND effective_to > ?
                """, effectiveTo, id, effectiveTo);
    }
}
