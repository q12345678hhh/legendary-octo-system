package com.acme.platform.identity.retention;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 登录账号的个人信息匿名化。
 *
 * <p>只替换邮箱与显示名，保留账号 ID、状态与时间戳；密码置空强制重新激活，
 * 避免匿名化后的账号还能用旧密码登录。
 */
@Component
public class AccountAnonymizer {

    private final JdbcTemplate jdbcTemplate;

    public AccountAnonymizer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return 受影响的账号数；同一哈希重复执行结果一致（幂等）。 */
    public int anonymizeByPerson(UUID personId, String hash8) {
        return jdbcTemplate.update("""
                UPDATE identity_user_account a
                SET email = 'anon-' || ? || '@invalid',
                    email_normalized = 'anon-' || ? || '@invalid',
                    display_name = '匿名用户-' || ?,
                    password_hash = NULL,
                    updated_at = now()
                WHERE a.id IN (SELECT user_id FROM identity_tenant_member WHERE person_id = ?)
                """, hash8, hash8, hash8, personId);
    }
}
