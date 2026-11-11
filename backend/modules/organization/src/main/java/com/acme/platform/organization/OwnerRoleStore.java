package com.acme.platform.organization;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 老板/受托人角色存储。 */
@Component
public class OwnerRoleStore {

    private static final RowMapper<OwnerRole> MAPPER = (rs, rowNum) -> new OwnerRole(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("employment_id", UUID.class),
            OwnerRole.RoleType.fromWire(rs.getString("role_type")),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getString("appointed_by"),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public OwnerRoleStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OwnerRole> find(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, employment_id, role_type, effective_from, effective_to,
                       appointed_by, created_at
                  FROM organization_owner_role WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public List<OwnerRole> list() {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, employment_id, role_type, effective_from, effective_to,
                       appointed_by, created_at
                  FROM organization_owner_role ORDER BY role_type, effective_from DESC
                """, MAPPER);
    }

    public List<OwnerRole> listCurrent(LocalDate today) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, employment_id, role_type, effective_from, effective_to,
                       appointed_by, created_at
                  FROM organization_owner_role
                 WHERE effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                 ORDER BY role_type, effective_from
                """, MAPPER, today, today);
    }

    public Set<UUID> currentEmploymentIds(OwnerRole.RoleType roleType, LocalDate today) {
        return new LinkedHashSet<>(jdbcTemplate.query("""
                SELECT employment_id FROM organization_owner_role
                 WHERE role_type = ?
                   AND effective_from <= ?
                   AND (effective_to IS NULL OR effective_to > ?)
                """, (rs, rowNum) -> rs.getObject("employment_id", UUID.class),
                roleType.wire(), today, today));
    }

    public Optional<OwnerRole> findOpen(UUID employmentId, OwnerRole.RoleType roleType) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, employment_id, role_type, effective_from, effective_to,
                       appointed_by, created_at
                  FROM organization_owner_role
                 WHERE employment_id = ? AND role_type = ? AND effective_to IS NULL
                """, MAPPER, employmentId, roleType.wire()).stream().findFirst();
    }

    public void insert(UUID id, UUID employmentId, OwnerRole.RoleType roleType,
                       LocalDate from, LocalDate to, String appointedBy) {
        jdbcTemplate.update("""
                INSERT INTO organization_owner_role
                    (id, tenant_id, employment_id, role_type, effective_from, effective_to,
                     appointed_by)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
                """, id, employmentId, roleType.wire(), from, to, appointedBy);
    }

    public int end(UUID id, LocalDate effectiveTo) {
        return jdbcTemplate.update("""
                UPDATE organization_owner_role
                   SET effective_to = ?, updated_at = now()
                 WHERE id = ? AND effective_to IS NULL
                """, effectiveTo, id);
    }
}
