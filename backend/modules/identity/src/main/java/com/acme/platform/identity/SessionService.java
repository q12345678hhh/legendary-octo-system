package com.acme.platform.identity;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 会话签发与校验。
 *
 * <p>所有查询都在请求租户的事务内执行：把 A 租户的令牌发到 B 租户时，RLS 直接返回 0 行，
 * 因此跨租户令牌不需要额外的拒绝分支。
 *
 * <p>校验同时检查四件事：会话未撤销/未过期、账号仍为 active、会话 auth_version 与账号一致、
 * 成员仍为 active。任一不满足立即撤销该会话并返回 401。
 */
@Service
public class SessionService {

    private final TenantTransactions tenantTransactions;
    private final SessionStore sessionStore;
    private final ApiTokenStore apiTokenStore;
    private final UserAccountStore userAccountStore;
    private final MemberStore memberStore;
    private final ActorStore actorStore;
    private final ServiceAccountStore serviceAccountStore;
    private final IdentityProperties properties;
    private final Clock clock;

    public SessionService(TenantTransactions tenantTransactions,
                          SessionStore sessionStore,
                          ApiTokenStore apiTokenStore,
                          UserAccountStore userAccountStore,
                          MemberStore memberStore,
                          ActorStore actorStore,
                          ServiceAccountStore serviceAccountStore,
                          IdentityProperties properties,
                          Clock clock) {
        this.tenantTransactions = tenantTransactions;
        this.sessionStore = sessionStore;
        this.apiTokenStore = apiTokenStore;
        this.userAccountStore = userAccountStore;
        this.memberStore = memberStore;
        this.actorStore = actorStore;
        this.serviceAccountStore = serviceAccountStore;
        this.properties = properties;
        this.clock = clock;
    }

    public record DeviceInfo(UUID deviceId, String deviceLabel, String ip, String userAgent) {
    }

    public record SessionIssued(String rawToken, UUID sessionId, OffsetDateTime expiresAt) {
    }

    /** 必须在调用方已打开的租户事务内执行，保证登录、审计与会话写入同生共死。 */
    public SessionIssued issue(UUID tenantId, UUID userId, UUID actorId, int authVersion,
                               boolean mfaSatisfied, DeviceInfo device) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime idleExpires = now.plus(properties.getSessionIdleTtl());
        OffsetDateTime expires = now.plus(properties.getSessionAbsoluteTtl());
        TokenHasher.IssuedToken token = TokenHasher.issue("s");
        UUID sessionId = UUID.randomUUID();
        sessionStore.insert(sessionId, userId, actorId, token.hash(), authVersion, mfaSatisfied,
                device == null ? null : device.deviceId(),
                device == null ? null : device.deviceLabel(),
                device == null ? null : device.ip(),
                device == null ? null : device.userAgent(),
                idleExpires, expires);
        return new SessionIssued(token.raw(), sessionId, expires);
    }

    public SessionPrincipal authenticate(UUID tenantId, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.badRequest("SESSION_REQUIRED", "缺少会话凭据");
        }
        return tenantTransactions.inTenant(contextFor(tenantId), ctx -> {
            SessionRecord session = sessionStore.findByTokenHash(TokenHasher.hash(rawToken))
                    .orElseThrow(() -> ApiException.badRequest("SESSION_INVALID", "会话无效或已过期"));
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (session.revokedAt() != null) {
                throw ApiException.badRequest("SESSION_REVOKED", "会话已撤销");
            }
            if (now.isAfter(session.expiresAt()) || now.isAfter(session.idleExpiresAt())) {
                sessionStore.revoke(session.id(), "expired", "system");
                throw ApiException.badRequest("SESSION_EXPIRED", "会话已过期");
            }
            UserAccount account = userAccountStore.findAccount(session.userId())
                    .orElseThrow(() -> ApiException.badRequest("SESSION_INVALID", "账号不存在"));
            if (account.status() != UserStatus.ACTIVE || account.authVersion() != session.authVersion()) {
                sessionStore.revoke(session.id(), "auth_version_changed", "system");
                throw ApiException.badRequest("SESSION_INVALID", "账号状态已变化，请重新登录");
            }
            TenantMember member = memberStore.findByUserId(account.id())
                    .orElseThrow(() -> ApiException.badRequest("MEMBER_REQUIRED", "当前账号不是租户成员"));
            if (member.status() != MemberStatus.ACTIVE) {
                sessionStore.revoke(session.id(), "member_inactive", "system");
                throw ApiException.badRequest("MEMBER_INACTIVE", "成员已停用");
            }
            Actor actor = actorStore.findById(session.actorId())
                    .orElseThrow(() -> ApiException.badRequest("SESSION_INVALID", "主体不存在"));
            if (!"active".equals(actor.status())) {
                sessionStore.revoke(session.id(), "actor_disabled", "system");
                throw ApiException.badRequest("ACTOR_DISABLED", "主体已禁用");
            }
            if (Duration.between(session.lastSeenAt(), now).toSeconds() >= 60) {
                sessionStore.touch(session.id(), now, now.plus(properties.getSessionIdleTtl()));
            }
            return new SessionPrincipal(tenantId, actor.id(), account.id(), member.id(), session.id(),
                    actor.actorType().wire(), account.email(), account.displayName(),
                    session.mfaSatisfied(), member.owner(), member.admin(), member.mfaRequired());
        });
    }

    public SessionPrincipal authenticateToken(UUID tenantId, String rawToken) {
        return tenantTransactions.inTenant(contextFor(tenantId), ctx -> {
            ApiToken token = apiTokenStore.findByTokenHash(TokenHasher.hash(rawToken))
                    .orElseThrow(() -> ApiException.badRequest("TOKEN_INVALID", "令牌无效或已过期"));
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (token.revokedAt() != null) {
                throw ApiException.badRequest("TOKEN_REVOKED", "令牌已撤销");
            }
            if (!now.isBefore(token.expiresAt())) {
                throw ApiException.badRequest("TOKEN_EXPIRED", "令牌已过期");
            }
            ServiceAccount account = serviceAccountStore.find(token.serviceAccountId())
                    .orElseThrow(() -> ApiException.badRequest("TOKEN_INVALID", "服务账号不存在"));
            if (!"active".equals(account.status())) {
                throw ApiException.badRequest("SERVICE_ACCOUNT_DISABLED", "服务账号已禁用");
            }
            Actor actor = actorStore.findById(token.actorId())
                    .orElseThrow(() -> ApiException.badRequest("TOKEN_INVALID", "主体不存在"));
            if (!"active".equals(actor.status())) {
                throw ApiException.badRequest("ACTOR_DISABLED", "主体已禁用");
            }
            apiTokenStore.touch(token.id());
            return new SessionPrincipal(tenantId, actor.id(), null, null, null,
                    actor.actorType().wire(), null, actor.displayName(),
                    false, false, false, false);
        });
    }

    public List<SessionRecord> list(UUID userId) {
        return tenantTransactions.inTenant(ctx -> sessionStore.listActiveByUser(userId));
    }

    public void revoke(UUID sessionId, SessionPrincipal principal, String reason) {
        revokeOwned(sessionId, principal.userId(), principal.actorId().toString(), reason);
    }

    public void revokeOwned(UUID sessionId, UUID userId, String actorId, String reason) {
        tenantTransactions.inTenant(ctx -> {
            SessionRecord session = sessionStore.findById(sessionId)
                    .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "会话不存在"));
            if (userId == null || !session.userId().equals(userId)) {
                throw ApiException.forbidden("SESSION_FORBIDDEN", "不能撤销其他账号的会话");
            }
            sessionStore.revoke(sessionId, reason, actorId);
            return null;
        });
    }

    public void revokeDevice(UUID userId, UUID deviceId, String actorId) {
        tenantTransactions.inTenant(ctx -> {
            sessionStore.revokeByDevice(userId, deviceId, "device_revoked", actorId);
            return null;
        });
    }

    public void revokeAll(UUID userId, String actorId, String reason) {
        tenantTransactions.inTenant(ctx -> {
            sessionStore.revokeAllForUser(userId, reason, actorId);
            return null;
        });
    }

    public void revokeAllForService(UUID serviceAccountId, String actorId) {
        tenantTransactions.inTenant(ctx -> {
            apiTokenStore.revokeAllForAccount(serviceAccountId, actorId);
            return null;
        });
    }

    public void markMfaSatisfied(UUID sessionId) {
        if (sessionId != null) {
            tenantTransactions.inTenant(ctx -> {
                sessionStore.markMfaSatisfied(sessionId);
                return null;
            });
        }
    }

    public Optional<SessionRecord> find(UUID sessionId) {
        return tenantTransactions.inTenant(ctx -> sessionStore.findById(sessionId));
    }

    private RequestContext contextFor(UUID tenantId) {
        Optional<RequestContext> current = RequestContextHolder.find();
        String correlationId = current.map(RequestContext::correlationId)
                .orElseGet(() -> UUID.randomUUID().toString());
        String traceId = current.map(RequestContext::traceId)
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", ""));
        return new RequestContext(tenantId, null, correlationId, traceId);
    }
}
