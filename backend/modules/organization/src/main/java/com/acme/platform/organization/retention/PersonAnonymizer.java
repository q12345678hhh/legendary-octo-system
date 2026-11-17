package com.acme.platform.organization.retention;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 自然人个人信息匿名化：替换显示名与联系邮箱，保留 ID、状态与组织结构关系。
 */
@Component
public class PersonAnonymizer {

    public record PersonState(boolean exists, boolean activeEmployment) {
    }

    private final JdbcTemplate jdbcTemplate;

    public PersonAnonymizer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PersonState state(UUID personId) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM organization_person WHERE id = ?", Integer.class, personId);
        Integer active = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM organization_employment
                WHERE person_id = ? AND status = 'active'
                """, Integer.class, personId);
        return new PersonState(exists != null && exists > 0, active != null && active > 0);
    }

    /** @return 受影响的行数；同一哈希重复执行结果一致（幂等）。 */
    public int anonymize(UUID personId, String hash8) {
        return jdbcTemplate.update("""
                UPDATE organization_person
                SET display_name = '匿名用户-' || ?,
                    primary_email = NULL,
                    updated_at = now()
                WHERE id = ?
                """, hash8, personId);
    }
}
