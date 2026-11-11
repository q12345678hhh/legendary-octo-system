package com.acme.platform.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 身份与会话配置。
 *
 * <p>{@code mfaKey} 是 32 字节 AES 密钥的 base64 编码，只从环境变量注入；
 * 未配置时禁止注册 MFA，但不影响无 MFA 用户登录。
 */
@ConfigurationProperties(prefix = "platform.identity")
public class IdentityProperties {

    private Duration sessionAbsoluteTtl = Duration.ofHours(8);
    private Duration sessionIdleTtl = Duration.ofMinutes(30);
    private Duration mfaChallengeTtl = Duration.ofMinutes(5);
    private Duration invitationTtl = Duration.ofHours(72);
    private Duration lockDuration = Duration.ofMinutes(15);
    private int maxFailedAttempts = 5;
    private boolean cookieSecure = true;
    private String cookieName = "plt_session";
    private String csrfCookieName = "plt_csrf";
    private String mfaKey;

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

    public Duration getMfaChallengeTtl() {
        return mfaChallengeTtl;
    }

    public void setMfaChallengeTtl(Duration mfaChallengeTtl) {
        this.mfaChallengeTtl = mfaChallengeTtl;
    }

    public Duration getInvitationTtl() {
        return invitationTtl;
    }

    public void setInvitationTtl(Duration invitationTtl) {
        this.invitationTtl = invitationTtl;
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

    public String getMfaKey() {
        return mfaKey;
    }

    public void setMfaKey(String mfaKey) {
        this.mfaKey = mfaKey;
    }
}
