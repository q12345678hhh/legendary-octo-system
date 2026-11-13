package com.acme.platform.worker.platformops;

import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.directory.PlatformDirectoryStore;
import com.acme.platform.platform.security.PlatformSecurityStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 异常登录扫描：逐租户统计近期登录失败次数，超阈值登记平台安全事件。
 *
 * <p>租户认证事件受 RLS 保护，因此必须逐个租户进入租户事务读取；平台侧拿到的只有
 * 「某租户失败 N 次」这一计数，不含账号、密码或请求体。
 */
@Component
public class SecuritySignalScanner {

    private static final Logger log = LoggerFactory.getLogger(SecuritySignalScanner.class);

    private final PlatformDirectoryStore directoryStore;
    private final TenantTransactions tenantTransactions;
    private final PlatformSecurityStore securityStore;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final int threshold;
    private final int windowMinutes;

    public SecuritySignalScanner(PlatformDirectoryStore directoryStore,
                                 TenantTransactions tenantTransactions,
                                 PlatformSecurityStore securityStore,
                                 JdbcTemplate jdbcTemplate,
                                 Clock clock,
                                 @Value("${platform.ops.abnormal-login-threshold:10}") int threshold,
                                 @Value("${platform.ops.abnormal-login-window-minutes:15}")
                                 int windowMinutes) {
        this.directoryStore = directoryStore;
        this.tenantTransactions = tenantTransactions;
        this.securityStore = securityStore;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.threshold = Math.max(1, threshold);
        this.windowMinutes = Math.max(1, windowMinutes);
    }

    @Scheduled(fixedDelayString = "${platform.ops.security-poll-interval:5m}",
            initialDelayString = "${platform.ops.security-initial-delay:2m}")
    public void scan() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime since = now.minusMinutes(windowMinutes);
        int raised = 0;
        for (UUID tenantId : directoryStore.tenantIds()) {
            try {
                int failures = tenantTransactions.inTenant(
                        new RequestContext(tenantId, null, "security-scan",
                                UUID.randomUUID().toString().replace("-", "")),
                        context -> countFailures(since));
                if (failures < threshold) {
                    continue;
                }
                boolean inserted = securityStore.insertIfAbsent(tenantId, "abnormal_login", since,
                        "high", "租户近 " + windowMinutes + " 分钟登录失败 " + failures + " 次",
                        "{\"failures\":" + failures + ",\"windowMinutes\":" + windowMinutes + "}");
                if (inserted) {
                    raised++;
                }
            } catch (RuntimeException failure) {
                log.warn("租户 {} 异常登录扫描失败：{}", tenantId, failure.getMessage());
            }
        }
        if (raised > 0) {
            log.info("平台安全扫描：新增 {} 条异常登录事件", raised);
        }
    }

    private int countFailures(OffsetDateTime since) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM identity_auth_event
                 WHERE outcome = 'failure' AND event_type IN ('login', 'mfa_verify')
                   AND occurred_at >= ?
                """, Integer.class, since);
        return value == null ? 0 : value;
    }
}
