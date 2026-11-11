package com.acme.platform.identity;

import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 登录编排：密码校验、失败锁定、MFA 挑战、会话签发与登出。
 *
 * <p>登录失败一律返回 401 {@code INVALID_CREDENTIALS}，不区分邮箱不存在与密码错误，
 * 避免通过错误码探测账号是否存在。
 */
@Service
public class AuthenticationService {

    private final TenantTransactions tenantTransactions;
    private final UserAccountStore userAccountStore;
    private final MemberStore memberStore;
    private final ActorStore actorStore;
    private final MfaStore mfaStore;
    private final MfaService mfaService;
    private final SessionService sessionService;
    private final PasswordHasher passwordHasher;
    private final AuthEventStore authEventStore;
    private final IdentityProperties properties;
    private final Clock clock;

    public AuthenticationService(TenantTransactions tenantTransactions,
                                 UserAccountStore userAccountStore,
                                 MemberStore memberStore,
                                 ActorStore actorStore,
                                 MfaStore mfaStore,
                                 MfaService mfaService,
                                 SessionService sessionService,
                                 PasswordHasher passwordHasher,
                                 AuthEventStore authEventStore,
                                 IdentityProperties properties,
                                 Clock clock) {
        this.tenantTransactions = tenantTransactions;
        this.userAccountStore = userAccountStore;
        this.memberStore = memberStore;
        this.actorStore = actorStore;
        this.mfaStore = mfaStore;
        this.mfaService = mfaService;
        this.sessionService = sessionService;
        this.passwordHasher = passwordHasher;
        this.authEventStore = authEventStore;
        this.properties = properties;
        this.clock = clock;
    }

    public record LoginCommand(String email, String password) {
    }

    public record MfaVerifyCommand(String mfaToken, String code) {
    }

    public record LoginResult(String status,
                              SessionPrincipal principal,
                              String sessionToken,
                              OffsetDateTime sessionExpiresAt,
                              String mfaToken,
                              OffsetDateTime mfaExpiresAt) {
    }

    public record MeView(UUID tenantId,
                         UUID actorId,
                         UUID userId,
                         UUID memberId,
                         String actorType,
                         String email,
                         String displayName,
                         boolean owner,
                         boolean admin,
                         boolean mfaRequired,
                         boolean mfaSatisfied,
                         boolean mfaEnabled) {
    }

    public LoginResult login(UUID tenantId, LoginCommand command, SessionService.DeviceInfo device) {
        if (command.email() == null || command.email().isBlank()
                || command.password() == null || command.password().isEmpty()) {
            throw ApiException.badRequest("CREDENTIALS_REQUIRED", "必须提供邮箱与密码");
        }
        String email = command.email().trim().toLowerCase();
        return tenantTransactions.inTenant(contextFor(tenantId), ctx -> {
            UserAccountStore.AccountWithSecret stored = userAccountStore.findByEmail(email).orElse(null);
            if (stored == null) {
                authEventStore.record("login", false, null, null,
                        device == null ? null : device.ip(), device == null ? null : device.userAgent());
                throw new ApiException(401, "INVALID_CREDENTIALS", "邮箱或密码不正确");
            }
            UserAccount account = stored.account();
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (account.lockedUntil() != null && now.isBefore(account.lockedUntil())) {
                throw new ApiException(423, "ACCOUNT_LOCKED", "失败次数过多，账号已临时锁定");
            }
            if (account.status() != UserStatus.ACTIVE) {
                throw new ApiException(403, "ACCOUNT_NOT_ACTIVE", "账号尚未激活或已被停用");
            }
            if (!passwordHasher.matches(command.password(), stored.passwordHash())) {
                userAccountStore.recordFailure(account.id(), properties.getMaxFailedAttempts(),
                        properties.getLockDuration());
                authEventStore.record("login", false, account.id(), null,
                        device == null ? null : device.ip(), device == null ? null : device.userAgent());
                throw new ApiException(401, "INVALID_CREDENTIALS", "邮箱或密码不正确");
            }
            userAccountStore.clearFailuresAndMarkLogin(account.id());
            TenantMember member = memberStore.findByUserId(account.id()).orElseThrow(() ->
                    new ApiException(403, "MEMBER_REQUIRED", "当前账号不是租户成员"));
            if (member.status() != MemberStatus.ACTIVE) {
                throw new ApiException(403, "MEMBER_INACTIVE", "成员已停用");
            }
            Actor actor = actorStore.findByUserId(account.id()).orElseThrow(() ->
                    new ApiException(403, "ACTOR_REQUIRED", "账号缺少主体记录"));
            if (mfaStore.findActive(account.id()).isPresent()) {
                TokenHasher.IssuedToken challenge = TokenHasher.issue("mfa");
                OffsetDateTime expires = now.plus(properties.getMfaChallengeTtl());
                mfaStore.insertChallenge(UUID.randomUUID(), account.id(), challenge.hash(), expires);
                return new LoginResult("mfa_required", null, null, null, challenge.raw(), expires);
            }
            boolean mfaSatisfied = !member.mfaRequired();
            SessionService.SessionIssued issued = sessionService.issue(tenantId, account.id(), actor.id(),
                    account.authVersion(), mfaSatisfied, device);
            authEventStore.record("login", true, account.id(), actor.id(),
                    device == null ? null : device.ip(), device == null ? null : device.userAgent());
            SessionPrincipal principal = new SessionPrincipal(tenantId, actor.id(), account.id(),
                    member.id(), issued.sessionId(), actor.actorType().wire(), account.email(),
                    account.displayName(), mfaSatisfied, member.owner(), member.admin(),
                    member.mfaRequired());
            return new LoginResult("session", principal, issued.rawToken(), issued.expiresAt(),
                    null, null);
        });
    }

    public LoginResult verifyMfa(UUID tenantId, MfaVerifyCommand command,
                                 SessionService.DeviceInfo device) {
        if (command.mfaToken() == null || command.mfaToken().isBlank()) {
            throw ApiException.badRequest("MFA_TOKEN_REQUIRED", "缺少 MFA 票据");
        }
        if (command.code() == null || command.code().isBlank()) {
            throw ApiException.badRequest("MFA_CODE_REQUIRED", "缺少验证码");
        }
        return tenantTransactions.inTenant(contextFor(tenantId), ctx -> {
            MfaStore.Challenge challenge = mfaStore.findChallenge(TokenHasher.hash(command.mfaToken().trim()))
                    .orElseThrow(() -> new ApiException(401, "MFA_TOKEN_INVALID", "MFA 票据无效"));
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (challenge.consumedAt() != null) {
                throw new ApiException(401, "MFA_TOKEN_CONSUMED", "MFA 票据已使用");
            }
            if (!now.isBefore(challenge.expiresAt())) {
                throw new ApiException(401, "MFA_TOKEN_EXPIRED", "MFA 票据已过期");
            }
            if (challenge.attempts() >= 10) {
                throw new ApiException(429, "MFA_ATTEMPTS_EXCEEDED", "验证次数过多，请重新登录");
            }
            if (!mfaService.verifyLogin(challenge.userId(), command.code())) {
                mfaStore.incrementAttempts(challenge.id());
                authEventStore.record("mfa_verify", false, challenge.userId(), null,
                        device == null ? null : device.ip(), device == null ? null : device.userAgent());
                throw new ApiException(401, "MFA_CODE_INVALID", "验证码不正确");
            }
            mfaStore.consumeChallenge(challenge.id());
            UserAccount account = userAccountStore.findAccount(challenge.userId()).orElseThrow(() ->
                    new ApiException(401, "INVALID_CREDENTIALS", "账号不存在"));
            TenantMember member = memberStore.findByUserId(account.id()).orElseThrow(() ->
                    new ApiException(403, "MEMBER_REQUIRED", "当前账号不是租户成员"));
            Actor actor = actorStore.findByUserId(account.id()).orElseThrow(() ->
                    new ApiException(403, "ACTOR_REQUIRED", "账号缺少主体记录"));
            SessionService.SessionIssued issued = sessionService.issue(tenantId, account.id(), actor.id(),
                    account.authVersion(), true, device);
            authEventStore.record("mfa_verify", true, account.id(), actor.id(),
                    device == null ? null : device.ip(), device == null ? null : device.userAgent());
            SessionPrincipal principal = new SessionPrincipal(tenantId, actor.id(), account.id(),
                    member.id(), issued.sessionId(), actor.actorType().wire(), account.email(),
                    account.displayName(), true, member.owner(), member.admin(), member.mfaRequired());
            return new LoginResult("session", principal, issued.rawToken(), issued.expiresAt(), null, null);
        });
    }

    public MeView me(ActorContext actor) {
        return tenantTransactions.inTenant(ctx -> {
            UserAccount account = actor.userId() == null ? null
                    : userAccountStore.findAccount(actor.userId()).orElse(null);
            TenantMember member = actor.memberId() == null ? null
                    : memberStore.findById(actor.memberId()).orElse(null);
            Actor actorRecord = actorStore.findById(actor.actorId()).orElse(null);
            boolean mfaEnabled = actor.userId() != null && mfaService.enabled(actor.userId());
            return new MeView(actor.tenantId(), actor.actorId(), actor.userId(), actor.memberId(),
                    actor.actorType(),
                    account == null ? null : account.email(),
                    account == null ? (actorRecord == null ? null : actorRecord.displayName())
                            : account.displayName(),
                    member != null && member.owner(),
                    member != null && member.admin(),
                    member != null && member.mfaRequired(),
                    actor.mfaSatisfied(),
                    mfaEnabled);
        });
    }

    public void logout(ActorContext actor) {
        if (actor.sessionId() == null) {
            return;
        }
        tenantTransactions.inTenant(ctx -> {
            sessionService.revokeOwned(actor.sessionId(), actor.userId(), actor.actorId().toString(),
                    "logout");
            authEventStore.record("logout", true, actor.userId(), actor.actorId(), null, null);
            return null;
        });
    }

    private com.acme.platform.platform.RequestContext contextFor(UUID tenantId) {
        java.util.Optional<com.acme.platform.platform.RequestContext> current =
                com.acme.platform.platform.RequestContextHolder.find();
        return new com.acme.platform.platform.RequestContext(tenantId, null,
                current.map(com.acme.platform.platform.RequestContext::correlationId)
                        .orElseGet(() -> UUID.randomUUID().toString()),
                current.map(com.acme.platform.platform.RequestContext::traceId)
                        .orElseGet(() -> UUID.randomUUID().toString().replace("-", "")));
    }
}
