package com.acme.platform.authorization;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 角色成员的只读公开查询：供工作流「按角色选审批人」使用。
 *
 * <p>只返回有效授权、未撤销、成员 active 且已绑定员工的记录；调用方必须已经在
 * {@code TenantTransactions.inTenant(...)} 事务里。
 */
@Component
public class RoleMembershipQueries {

    private final JdbcTemplate jdbcTemplate;

    public RoleMembershipQueries(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<UUID> personIdsWithRole(String roleCode, LocalDate today) {
        if (roleCode == null || roleCode.isBlank() || today == null) {
            return List.of();
        }
        OffsetDateTime start = today.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusDays(1);
        return jdbcTemplate.query("""
                SELECT DISTINCT m.person_id
                  FROM authorization_assignment a
                  JOIN identity_tenant_member m
                    ON m.id = a.member_id AND m.tenant_id = a.tenant_id
                 WHERE a.role_code = ?
                   AND a.revoked_at IS NULL
                   AND a.valid_from < ?
                   AND (a.valid_to IS NULL OR a.valid_to > ?)
                   AND m.status = 'active'
                   AND m.person_id IS NOT NULL
                 ORDER BY m.person_id
                """, (rs, rowNum) -> rs.getObject("person_id", UUID.class),
                roleCode, end, start);
    }
}
