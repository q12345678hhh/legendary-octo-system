package com.acme.platform.authorization;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 授权决策配置。
 *
 * <p>缓存 TTL 与临时授权上限都是安全口径，不写死在代码里：生产可以缩短，
 * 但不能超过 PLAN 的 60 秒失效目标。
 */
@ConfigurationProperties(prefix = "platform.authorization")
public class AuthorizationProperties {

    private Duration cacheTtl = Duration.ofSeconds(60);
    private Duration versionTtl = Duration.ofSeconds(1);
    private int maxTemporaryGrantDays = 90;
    private int maxDelegationDays = 180;

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public Duration getVersionTtl() {
        return versionTtl;
    }

    public void setVersionTtl(Duration versionTtl) {
        this.versionTtl = versionTtl;
    }

    public int getMaxTemporaryGrantDays() {
        return maxTemporaryGrantDays;
    }

    public void setMaxTemporaryGrantDays(int maxTemporaryGrantDays) {
        this.maxTemporaryGrantDays = maxTemporaryGrantDays;
    }

    public int getMaxDelegationDays() {
        return maxDelegationDays;
    }

    public void setMaxDelegationDays(int maxDelegationDays) {
        this.maxDelegationDays = maxDelegationDays;
    }
}
