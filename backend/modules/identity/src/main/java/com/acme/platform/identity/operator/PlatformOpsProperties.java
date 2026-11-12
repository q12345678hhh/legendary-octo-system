package com.acme.platform.identity.operator;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台运营端配置。
 *
 * <p>Cookie 名与租户会话刻意不同：{@code plt_ops_session} 只挂在 {@code Path=/platform}，
 * 浏览器不会把它带进 {@code /api/**}，反之亦然。
 */
@ConfigurationProperties(prefix = "platform.ops")
public class PlatformOpsProperties {

    private Duration sessionAbsoluteTtl = Duration.ofHours(8);
    private Duration sessionIdleTtl = Duration.ofMinutes(30);
    private Duration lockDuration = Duration.ofMinutes(15);
    private int maxFailedAttempts = 5;
    private boolean cookieSecure = true;
    private String cookieName = "plt_ops_session";
    private String csrfCookieName = "plt_ops_csrf";
    private String cookiePath = "/platform";

    public Duration getSessionAbsoluteTtl() {
        return sessionAbsoluteTtl;
    }

    public void setSessionAbsoluteTtl(Duration sessionAbsoluteTtl) {
        this.sessionAbsoluteTtl = sessionAbsoluteTtl;
    }

    public Duration getSessionIdleTtl() {
        return sessionIdleTtl;
    }

    public void setSessionIdleTtl(Duration sessionIdleTtl) {
        this.sessionIdleTtl = sessionIdleTtl;
    }

    public Duration getLockDuration() {
        return lockDuration;
    }

    public void setLockDuration(Duration lockDuration) {
        this.lockDuration = lockDuration;
    }

    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        this.maxFailedAttempts = maxFailedAttempts;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public String getCsrfCookieName() {
        return csrfCookieName;
    }

    public void setCsrfCookieName(String csrfCookieName) {
        this.csrfCookieName = csrfCookieName;
    }

    public String getCookiePath() {
        return cookiePath;
    }

    public void setCookiePath(String cookiePath) {
        this.cookiePath = cookiePath;
    }
}
