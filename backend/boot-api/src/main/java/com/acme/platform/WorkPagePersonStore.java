package com.acme.platform;

import com.acme.platform.platform.TenantTransactions;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 个人工作页的人员头部信息；只读展示名与在职状态。 */
@Component
class WorkPagePersonStore {

    private final JdbcTemplate jdbcTemplate;
    private final TenantTransactions transactions;

    WorkPagePersonStore(JdbcTemplate jdbcTemplate, TenantTransactions transactions) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
    }

    Optional<PersonHeader> find(UUID personId) {
        return transactions.inTenant(context -> jdbcTemplate.query("""
                        SELECT id, display_name, status
                          FROM organization_person
                         WHERE id = ?
                        """, (rs, rowNum) -> new PersonHeader(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("status")),
                personId).stream().findFirst());
    }

    record PersonHeader(UUID personId, String displayName, String status) {
    }
}
