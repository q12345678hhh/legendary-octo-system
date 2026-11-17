package com.acme.platform.worker.retention;

import com.acme.platform.platform.security.PlatformSecurityStore;
import com.acme.platform.tenancy.retention.RetentionPolicy;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审计到期扫描：审计只读不删，到期只登记安全事件提示，不做任何删除。
 */
@Component
public class RetentionDueScanner {

    private static final Logger log = LoggerFactory.getLogger(RetentionDueScanner.class);

    private final JdbcTemplate jdbcTemplate;
    private final RetentionPolicy policy;
    private final PlatformSecurityStore securityStore;

    public RetentionDueScanner(JdbcTemplate jdbcTemplate, RetentionPolicy policy,
                               PlatformSecurityStore securityStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.policy = policy;
        this.securityStore = securityStore;
    }

    @Scheduled(fixedDelayString = "${platform.retention.due-scan-interval:24h}",
            initialDelayString = "${platform.retention.due-scan-initial-delay:10m}")
    public void scan() {
        int auditDays = policy.auditDays();
        Integer due = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform_audit_event
                 WHERE occurred_at < now() - make_interval(days => ?)
                """, Integer.class, auditDays);
        if (due == null || due == 0) {
            return;
        }
        boolean recorded = securityStore.insertIfAbsent(null, "retention_due",
                OffsetDateTime.now().minusDays(1), "low",
                "审计记录已超过保留期 " + auditDays + " 天，共 " + due + " 条；按策略只读保留，不自动删除",
                "{\"due\":" + due + ",\"auditDays\":" + auditDays + "}");
        if (recorded) {
            log.warn("审计保留到期：{} 条超过 {} 天", due, auditDays);
        }
    }
}
