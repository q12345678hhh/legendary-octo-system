package com.acme.platform.identity.operator;

import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.PasswordHasher;
import com.acme.platform.identity.TokenHasher;
import com.acme.platform.identity.Totp;
import com.acme.platform.identity.TotpSecretCipher;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.security.PlatformSecurityRecorder;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 平台运营登录、会话与 MFA。
 *
 * <p>所有查询都直接落平台域表，不打开任何租户事务；失败原因对外只给
 * {@code PLATFORM_LOGIN_FAILED}，不区分「账号不存在」与「密码错误」，避免枚举账号。
 */
@Service
public class PlatformOperatorService {

    public record LoginResult(String sessionToken, PlatformActorContext actor,
                              OffsetDateTime expiresAt) {
    }

    public record MeView(UUID operatorId, String email, String displayName, String roleCode,
                         boolean mfaSatisfied, List<String> permissions) {
    }

    public record MfaEnrollment(String secret, String otpauthUri) {
    }

    private final PlatformOperatorStore operatorStore;
    private final PlatformOperatorSessionStore sessionStore;
    private final PasswordHasher passwordHasher;
    private final TotpSecretCipher totpCipher;
    private final IdentityProperties identityProperties;
    private final PlatformOpsProperties properties;
    private final PlatformSecurityRecorder securityRecorder;
    private final Clock clock;

    public PlatformOperatorService(PlatformOperatorStore operatorStore,
                                   PlatformOperatorSessionStore sessionStore,
                                   PasswordHasher passwordHasher,
                                   TotpSecretCipher totpCipher,
                                   IdentityProperties identityProperties,
                                   PlatformOpsProperties properties,
                                   PlatformSecurityRecorder securityRecorder,
                                   Clock clock) {
        this.operatorStore = operatorStore;
        this.sessionStore = sessionStore;
        this.passwordHasher = passwordHasher;
        this.totpCipher = totpCipher;
        this.identityProperties = identityProperties;
        this.properties = properties;
        this.securityRecorder = securityRecorder;
        this.clock = clock;
    }

    public LoginResult login(String email, String password, String totpCode, String ip,
                             String userAgent) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || password == null || password.isEmpty()) {
            throw loginFailed();
        }
        Optional<PlatformOperator> found = operatorStore.findByEmail(normalized);
        if (found.isEmpty()) {
            throw loginFailed();
        }
        PlatformOperator operator = found.get();
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (operator.locked(now)) {
            throw new ApiException(423, "PLATFORM_OPERATOR_LOCKED",
                    "账号已临时锁定，请稍后再试");
        }
        if (!operator.active()) {
            throw ApiException.forbidden("PLATFORM_OPERATOR_DISABLED", "该运营账号已停用");
        }
        if (!passwordHasher.matches(password, operator.passwordHash())) {
            failed(operator, now);
            throw loginFailed();
        }

        boolean mfaSatisfied = false;
        if (operator.mfaConfirmed()) {
            if (totpCode == null || totpCode.isBlank()) {
                throw new ApiException(401, "PLATFORM_MFA_REQUIRED", "该账号已绑定 MFA，请提交动态码");
            }
            String secret = totpCipher.decrypt(operator.mfaSecretCipher(), operator.mfaNonce());
            if (Totp.verify(secret, totpCode.trim(), now.toEpochSecond(), null) < 0) {
                failed(operator, now);
                throw new ApiException(401, "PLATFORM_MFA_INVALID", "动态码不正确或已过期");
            }
            mfaSatisfied = true;
        }

        operatorStore.recordSuccess(operator.id(), now);
        TokenHasher.IssuedToken token = TokenHasher.issue("ops");
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime idleExpires = now.plus(properties.getSessionIdleTtl());
        OffsetDateTime expires = now.plus(properties.getSessionAbsoluteTtl());
        sessionStore.insert(sessionId, operator.id(), token.hash(), mfaSatisfied, ip, userAgent,
                idleExpires, expires);
        return new LoginResult(token.raw(), context(operator, sessionId, mfaSatisfied), expires);
    }

    public PlatformActorContext authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiException(401, "PLATFORM_SESSION_REQUIRED", "平台接口必须使用运营账号登录");
        }
        String tokenHash = TokenHasher.hash(rawToken);
        PlatformOperatorSession session = sessionStore.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ApiException(401, "PLATFORM_SESSION_INVALID",
                        "运营会话不存在或已失效"));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!session.usable(now)) {
            sessionStore.revoke(session.id(), "expired");
            throw new ApiException(401, "PLATFORM_SESSION_EXPIRED", "运营会话已过期，请重新登录");
        }
        PlatformOperator operator = operatorStore.findById(session.operatorId())
                .orElseThrow(() -> new ApiException(401, "PLATFORM_SESSION_INVALID",
                        "运营账号不存在"));
        if (!operator.active()) {
            sessionStore.revoke(session.id(), "operator_disabled");
            throw ApiException.forbidden("PLATFORM_OPERATOR_DISABLED", "该运营账号已停用");
        }
        sessionStore.touch(session.id(), now, now.plus(properties.getSessionIdleTtl()));
        return context(operator, session.id(), session.mfaSatisfied());
    }

    public void logout(PlatformActorContext actor) {
        sessionStore.revoke(actor.sessionId(), "logout");
    }

    public MeView me(PlatformActorContext actor) {
        return new MeView(actor.operatorId(), actor.email(), actor.displayName(), actor.roleCode(),
                actor.mfaSatisfied(), List.copyOf(actor.permissions()));
    }

    public MfaEnrollment startMfa(PlatformActorContext actor) {
        PlatformOperator operator = operatorStore.findById(actor.operatorId())
                .orElseThrow(() -> ApiException.notFound("PLATFORM_OPERATOR_NOT_FOUND", "运营账号不存在"));
        if (operator.mfaConfirmed()) {
            throw ApiException.conflict("PLATFORM_MFA_ALREADY_BOUND", "该账号已绑定 MFA");
        }
        String secret = Totp.generateSecret();
        TotpSecretCipher.Encrypted encrypted = totpCipher.encrypt(secret);
        // 先落密文但保持 confirmed=false，确认动态码后才真正启用
        operatorStore.bindMfa(operator.id(), encrypted.ciphertext(), encrypted.nonce(), false,
                OffsetDateTime.now(clock));
        return new MfaEnrollment(secret,
                Totp.otpauthUri("Enterprise Platform 运营端", operator.email(), secret));
    }

    public void confirmMfa(PlatformActorContext actor, String code) {
        PlatformOperator operator = operatorStore.findById(actor.operatorId())
                .orElseThrow(() -> ApiException.notFound("PLATFORM_OPERATOR_NOT_FOUND", "运营账号不存在"));
        if (operator.mfaSecretCipher() == null || operator.mfaNonce() == null) {
            throw ApiException.badRequest("PLATFORM_MFA_NOT_STARTED", "请先开始绑定 MFA");
        }
        String secret = totpCipher.decrypt(operator.mfaSecretCipher(), operator.mfaNonce());
        if (Totp.verify(secret, code, OffsetDateTime.now(clock).toEpochSecond(), null) < 0) {
            throw new ApiException(401, "PLATFORM_MFA_INVALID", "动态码不正确或已过期");
        }
        operatorStore.confirmMfa(operator.id());
        sessionStore.markMfaSatisfied(actor.sessionId());
    }

    private void failed(PlatformOperator operator, OffsetDateTime now) {
        int attempts = operator.failedAttempts() + 1;
        OffsetDateTime lockedUntil = attempts >= properties.getMaxFailedAttempts()
                ? now.plus(properties.getLockDuration()) : null;
        operatorStore.recordFailure(operator.id(), attempts, lockedUntil, now);
        if (lockedUntil != null) {
            securityRecorder.abnormalLogin(operator.id(), operator.email(), attempts);
        }
    }

    private PlatformActorContext context(PlatformOperator operator, UUID sessionId,
                                         boolean mfaSatisfied) {
        return new PlatformActorContext(operator.id(), sessionId, operator.email(),
                operator.displayName(), operator.roleCode(), mfaSatisfied);
    }

    private ApiException loginFailed() {
        return new ApiException(401, "PLATFORM_LOGIN_FAILED", "邮箱或密码不正确");
    }
}
