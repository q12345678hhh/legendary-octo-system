package com.acme.platform;

import com.acme.platform.platform.TenantTransactions;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 部门首屏的「本人已上传文件数」投影。
 *
 * <p>只统计归属该人员登录主体的文件元数据，不返回文件名或链接；权限判定由控制器完成。
 */
@Component
class DepartmentFileCountQuery {

    private final JdbcTemplate jdbcTemplate;
    private final TenantTransactions transactions;

    DepartmentFileCountQuery(JdbcTemplate jdbcTemplate, TenantTransactions transactions) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
    }

    Map<UUID, Integer> counts(Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(personIds.size(), "?"));
        return transactions.inTenant(context -> {
            Map<UUID, Integer> result = new HashMap<>();
            jdbcTemplate.query("""
                    SELECT m.person_id, count(f.id) AS file_count
                      FROM identity_tenant_member m
                      JOIN identity_actor a
                        ON a.user_id = m.user_id
                       AND a.tenant_id = m.tenant_id
                       AND a.actor_type = 'user'
                      JOIN platform_file f
                        ON f.subject_id = a.id::text
                       AND f.tenant_id = m.tenant_id
                       AND f.status <> 'recycled'
                     WHERE m.person_id IN (%s)
                     GROUP BY m.person_id
                    """.formatted(placeholders), (rs) -> {
                result.put(rs.getObject("person_id", UUID.class),
                        rs.getInt("file_count"));
            }, personIds.toArray());
            return Map.copyOf(result);
        });
    }
}
