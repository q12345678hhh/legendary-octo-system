package com.acme.platform.identity;

import com.acme.platform.organization.PersonStore;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 邀请、激活与管理员重置密码。
 *
 * <p>P0 没有邮件服务：创建邀请/重置密码的响应直接返回一次性令牌，由管理员线下转交；
 * 库里只存 SHA-256，令牌一次性且默认 72 小时过期。P1 接入邮件后改为只投递不返回。
 */
@Service
public class InvitationService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final TenantTransactions tenantTransactions;
    private final UserAccountStore userAccountStore;
    private final ActorStore actorStore;
    private final MemberStore memberStore;
    private final InvitationStore invitationStore;
    private final PersonStore personStore;
    private final PasswordHasher passwordHasher;
    private final PasswordPolicy passwordPolicy;
    private final SessionService sessionService;
    private final AuthEventStore authEventStore;
    private final IdentityProperties properties;
    private final Clock clock;

    public InvitationService(TenantTransactions tenantTransactions,
                             UserAccountStore userAccountStore,
                             ActorStore actorStore,
                             MemberStore memberStore,
                             InvitationStore invitationStore,
                             PersonStore personStore,
                             PasswordHasher passwordHasher,
                             PasswordPolicy passwordPolicy,
                             SessionService sessionService,
                             AuthEventStore authEventStore,
                             IdentityProperties properties,
                             Clock clock) {
        this.tenantTransactions = tenantTransactions;
        this.userAccountStore = userAccountStore;
        this.actorStore = actorStore;
        this.memberStore = memberStore;
        this.invitationStore = invitationStore;
        this.personStore = personStore;
        this.passwordHasher = passwordHasher;
        this.passwordPolicy = passwordPolicy;
        this.sessionService = sessionService;
        this.authEventStore = authEventStore;
        this.properties = properties;
        this.clock = clock;
    }

    public record CreateInvitationCommand(String email,
                                          String displayName,
                                          UUID personId,
                                          Boolean admin,
                                          Boolean mfaRequired) {
    }

    public record InvitationIssued(UUID memberId,
                                   UUID userId,
                                   String email,
                                   String activationToken,
                                   OffsetDateTime expiresAt,
                                   boolean owner,
                                   boolean admin,
                                   boolean mfaRequired) {
    }

    public record AcceptInvitationCommand(String activationToken, String password) {
    }

    public record AcceptedInvitation(SessionPrincipal principal,
                                     String sessionToken,
                                     OffsetDateTime sessionExpiresAt,
                                     UUID memberId) {
    }

    public InvitationIssued create(CreateInvitationCommand command) {
        String email = normalizeEmail(command.email());
        String displayName = command.displayName() == null || command.displayName().isBlank()
                ? email : command.displayName().trim();
        try {
            return tenantTransactions.inTenant(ctx -> {
                boolean firstMember = memberStore.list(1).isEmpty();
                boolean owner = firstMember;
                boolean admin = owner || Boolean.TRUE.equals(command.admin());
                boolean mfaRequired = owner || Boolean.TRUE.equals(command.mfaRequired());
                if (command.personId() != null) {
                    if (!personStore.exists(command.personId())) {
                        throw ApiException.notFound("PERSON_NOT_FOUND",
                                "自然人不存在：" + command.personId());
                    }
                    if (memberStore.findByPersonId(command.personId()).isPresent()) {
                        throw ApiException.conflict("MEMBER_FOR_PERSON_EXISTS",
                                "该自然人已经绑定成员关系，请使用账号更换接口");
                    }
                }
                UUID userId = UUID.randomUUID();
                UUID actorId = UUID.randomUUID();
                UUID memberId = UUID.randomUUID();
                userAccountStore.insertInvited(userId, email, email, displayName);
                actorStore.insert(actorId, ActorType.USER, displayName, userId);
                memberStore.insert(memberId, userId, command.personId(), MemberStatus.INVITED,
                        owner, admin, mfaRequired);
                TokenHasher.IssuedToken token = TokenHasher.issue("inv");
                OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(properties.getInvitationTtl());
                invitationStore.insert(UUID.randomUUID(), userId, memberId, email, token.hash(),
                        expiresAt, currentActor());
                return new InvitationIssued(memberId, userId, email, token.raw(), expiresAt,
                        owner, admin, mfaRequired);
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("EMAIL_ALREADY_EXISTS", "该邮箱已存在账号：" + email);
        }
    }

    public AcceptedInvitation accept(UUID tenantId, AcceptInvitationCommand command,
                                     SessionService.DeviceInfo device) {
        if (command.activationToken() == null || command.activationToken().isBlank()) {
            throw ApiException.badRequest("INVITATION_TOKEN_REQUIRED", "缺少激活令牌");
        }
        return tenantTransactions.inTenant(contextFor(tenantId), ctx -> {
            Invitation invitation = invitationStore.findByTokenHash(
                            TokenHasher.hash(command.activationToken().trim()))
                    .orElseThrow(() -> new ApiException(401, "INVITATION_INVALID", "激活令牌无效"));
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (invitation.acceptedAt() != null) {
                throw ApiException.conflict("INVITATION_ALREADY_ACCEPTED", "邀请已被使用");
            }
            if (invitation.revokedAt() != null) {
                throw new ApiException(401, "INVITATION_REVOKED", "邀请已撤销");
            }
            if (!now.isBefore(invitation.expiresAt())) {
                throw new ApiException(401, "INVITATION_EXPIRED", "邀请已过期");
            }
            UserAccount account = userAccountStore.findAccount(invitation.userId())
                    .orElseThrow(() -> ApiException.badRequest("INVITATION_INVALID", "账号不存在"));
            if (account.status() != UserStatus.INVITED && account.status() != UserStatus.ACTIVE) {
                throw ApiException.conflict("ACCOUNT_NOT_ACTIVATABLE", "账号当前状态不能激活");
            }
            List<String> history = userAccountStore.recentPasswordHashes(account.id(),
                    PasswordPolicy.HISTORY_SIZE);
            passwordPolicy.validate(command.password(), account.email(), history, passwordHasher);
            String passwordHash = passwordHasher.hash(command.password());
            userAccountStore.activate(account.id(), passwordHash);
            userAccountStore.insertPasswordHistory(account.id(), passwordHash);
            memberStore.updateStatus(invitation.memberId(), MemberStatus.ACTIVE);
            invitationStore.markAccepted(invitation.id());
            TenantMember member = memberStore.findById(invitation.memberId()).orElseThrow();
            Actor actor = actorStore.findByUserId(account.id()).orElseThrow();
            int authVersion = userAccountStore.authVersion(account.id()).orElse(1);
            SessionService.SessionIssued session = sessionService.issue(tenantId, account.id(),
                    actor.id(), authVersion, !member.mfaRequired(), device);
            authEventStore.record("invitation_accept", true, account.id(), actor.id(),
                    device == null ? null : device.ip(), device == null ? null : device.userAgent());
            SessionPrincipal principal = new SessionPrincipal(tenantId, actor.id(), account.id(),
                    member.id(), session.sessionId(), ActorType.USER.wire(), account.email(),
                    account.displayName(), !member.mfaRequired(), member.owner(), member.admin(),
                    member.mfaRequired());
            return new AcceptedInvitation(principal, session.rawToken(), session.expiresAt(),
                    member.id());
        });
    }

    public InvitationIssued resetPassword(UUID memberId) {
        return tenantTransactions.inTenant(ctx -> {
            TenantMember member = memberStore.findById(memberId).orElseThrow(() ->
                    ApiException.notFound("MEMBER_NOT_FOUND", "成员不存在：" + memberId));
            UserAccount account = userAccountStore.findAccount(member.userId()).orElseThrow(() ->
                    ApiException.notFound("USER_NOT_FOUND", "账号不存在"));
            if (account.status() == UserStatus.DISABLED || account.status() == UserStatus.SUSPENDED) {
                throw ApiException.conflict("ACCOUNT_NOT_RESETTABLE", "停用或暂停的账号不能重置密码");
            }
            String actorId = currentActor();
            sessionService.revokeAll(account.id(), actorId, "password_reset");
            userAccountStore.bumpAuthVersion(account.id());
            invitationStore.revokeActiveForMember(memberId);
            TokenHasher.IssuedToken token = TokenHasher.issue("rst");
            OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(properties.getInvitationTtl());
            invitationStore.insert(UUID.randomUUID(), account.id(), memberId, account.email(),
                    token.hash(), expiresAt, actorId);
            authEventStore.record("password_reset", true, account.id(), currentActorId(),
                    null, null);
            return new InvitationIssued(memberId, account.id(), account.email(), token.raw(),
                    expiresAt, member.owner(), member.admin(), member.mfaRequired());
        });
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank() || email.trim().length() > 320
                || !EMAIL.matcher(email.trim()).matches()) {
            throw ApiException.unprocessable("EMAIL_INVALID", "邮箱格式不正确");
        }
        return email.trim().toLowerCase();
    }

    private String currentActor() {
        return com.acme.platform.platform.ActorContextHolder.current()
                .map(actor -> actor.actorId().toString()).orElse("system");
    }

    private UUID currentActorId() {
        return com.acme.platform.platform.ActorContextHolder.current()
                .map(actor -> actor.actorId()).orElse(null);
    }

    private RequestContext contextFor(UUID tenantId) {
        Optional<RequestContext> current = RequestContextHolder.find();
        return new RequestContext(tenantId, null,
                current.map(RequestContext::correlationId).orElseGet(() -> UUID.randomUUID().toString()),
                current.map(RequestContext::traceId)
                        .orElseGet(() -> UUID.randomUUID().toString().replace("-", "")));
    }
}
