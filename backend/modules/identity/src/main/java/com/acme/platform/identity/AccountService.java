package com.acme.platform.identity;

import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 成员生命周期：启停、管理员标志、强制 MFA、账号更换与自助改密。
 *
 * <p>停用成员会同时停用账号与主体、撤销全部会话并递增 {@code auth_version}，
 * 因此旧会话在下一次校验时立即失效，而不是等过期。
 */
@Service
public class AccountService {

    private final TenantTransactions tenantTransactions;
    private final UserAccountStore userAccountStore;
    private final ActorStore actorStore;
    private final MemberStore memberStore;
    private final PasswordHasher passwordHasher;
    private final PasswordPolicy passwordPolicy;
    private final SessionService sessionService;
    private final AuthEventStore authEventStore;

    public AccountService(TenantTransactions tenantTransactions,
                          UserAccountStore userAccountStore,
                          ActorStore actorStore,
                          MemberStore memberStore,
                          PasswordHasher passwordHasher,
                          PasswordPolicy passwordPolicy,
                          SessionService sessionService,
                          AuthEventStore authEventStore) {
        this.tenantTransactions = tenantTransactions;
        this.userAccountStore = userAccountStore;
        this.actorStore = actorStore;
        this.memberStore = memberStore;
        this.passwordHasher = passwordHasher;
        this.passwordPolicy = passwordPolicy;
        this.sessionService = sessionService;
        this.authEventStore = authEventStore;
    }

    public record MemberView(UUID id,
                             UUID userId,
                             UUID personId,
                             String email,
                             String displayName,
                             String status,
                             boolean owner,
                             boolean admin,
                             boolean mfaRequired) {
    }

    public record UpdateMemberCommand(MemberStatus status, Boolean admin, Boolean mfaRequired) {
    }

    public record ChangeAccountCommand(UUID newUserId, String reason) {
    }

    public record ChangePasswordCommand(String currentPassword, String newPassword) {
    }

    public List<MemberView> list() {
        return tenantTransactions.inTenant(ctx -> {
            List<MemberView> views = new ArrayList<>();
            for (TenantMember member : memberStore.list(200)) {
                views.add(toView(member));
            }
            return views;
        });
    }

    public MemberView update(UUID memberId, UpdateMemberCommand command, ActorContext actor) {
        return tenantTransactions.inTenant(ctx -> {
            TenantMember member = memberStore.findById(memberId).orElseThrow(() ->
                    ApiException.notFound("MEMBER_NOT_FOUND", "成员不存在：" + memberId));
            if (member.userId().equals(actor.userId())
                    && command.status() != null && command.status() != MemberStatus.ACTIVE) {
                throw ApiException.conflict("CANNOT_DISABLE_SELF", "不能停用自己的账号");
            }
            if (command.status() != null) {
                applyStatus(member, command.status(), actor);
            }
            if (command.admin() != null || command.mfaRequired() != null) {
                memberStore.updateFlags(member.id(), command.admin(), command.mfaRequired());
                if (command.mfaRequired() != null) {
                    userAccountStore.setMfaRequired(member.userId(), command.mfaRequired());
                }
            }
            return toView(memberStore.findById(memberId).orElseThrow());
        });
    }

    public MemberView changeAccount(UUID memberId, ChangeAccountCommand command, ActorContext actor) {
        if (command.newUserId() == null) {
            throw ApiException.unprocessable("NEW_ACCOUNT_REQUIRED", "缺少新账号");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw ApiException.unprocessable("REASON_REQUIRED", "账号更换必须说明原因");
        }
        return tenantTransactions.inTenant(ctx -> {
            TenantMember member = memberStore.findById(memberId).orElseThrow(() ->
                    ApiException.notFound("MEMBER_NOT_FOUND", "成员不存在：" + memberId));
            if (member.userId().equals(command.newUserId())) {
                throw ApiException.conflict("ACCOUNT_UNCHANGED", "新账号与当前账号相同");
            }
            UserAccount newAccount = userAccountStore.findAccount(command.newUserId()).orElseThrow(() ->
                    ApiException.notFound("USER_NOT_FOUND", "新账号不存在"));
            if (newAccount.status() == UserStatus.DISABLED) {
                throw ApiException.conflict("ACCOUNT_DISABLED", "新账号已停用");
            }
            UUID oldUserId = member.userId();
            memberStore.insertAccountHistory(memberId, oldUserId, newAccount.id(),
                    actor.actorId().toString(), command.reason().trim());
            userAccountStore.setStatus(oldUserId, UserStatus.DISABLED);
            Actor oldActor = actorStore.findByUserId(oldUserId).orElse(null);
            if (oldActor != null) {
                actorStore.disable(oldActor.id());
            }
            sessionService.revokeAll(oldUserId, actor.actorId().toString(), "account_replaced");
            memberStore.updateUserId(memberId, newAccount.id());
            userAccountStore.setMfaRequired(newAccount.id(), member.mfaRequired());
            return toView(memberStore.findById(memberId).orElseThrow());
        });
    }

    public void changePassword(ChangePasswordCommand command, ActorContext actor) {
        if (actor.userId() == null) {
            throw ApiException.forbidden("USER_REQUIRED", "该操作仅限登录用户");
        }
        tenantTransactions.inTenant(ctx -> {
            UserAccountStore.AccountWithSecret stored = userAccountStore.findById(actor.userId())
                    .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "账号不存在"));
            if (!passwordHasher.matches(command.currentPassword(), stored.passwordHash())) {
                throw ApiException.forbidden("CURRENT_PASSWORD_INVALID", "当前密码不正确");
            }
            List<String> history = userAccountStore.recentPasswordHashes(actor.userId(),
                    PasswordPolicy.HISTORY_SIZE);
            passwordPolicy.validate(command.newPassword(), stored.account().email(), history,
                    passwordHasher);
            String hash = passwordHasher.hash(command.newPassword());
            userAccountStore.updatePassword(actor.userId(), hash);
            userAccountStore.insertPasswordHistory(actor.userId(), hash);
            sessionService.revokeAll(actor.userId(), actor.actorId().toString(), "password_change");
            authEventStore.record("password_change", true, actor.userId(), actor.actorId(), null, null);
            return null;
        });
    }

    private void applyStatus(TenantMember member, MemberStatus status, ActorContext actor) {
        memberStore.updateStatus(member.id(), status);
        if (status == MemberStatus.DISABLED || status == MemberStatus.SUSPENDED) {
            userAccountStore.setStatus(member.userId(),
                    status == MemberStatus.DISABLED ? UserStatus.DISABLED : UserStatus.SUSPENDED);
            actorStore.findByUserId(member.userId()).ifPresent(actorRecord -> actorStore.disable(actorRecord.id()));
            sessionService.revokeAll(member.userId(), actor.actorId().toString(), "member_" + status.wire());
            authEventStore.record("member_disable", true, member.userId(), actor.actorId(), null, null);
        } else if (status == MemberStatus.ACTIVE) {
            userAccountStore.setStatus(member.userId(), UserStatus.ACTIVE);
            actorStore.findByUserId(member.userId()).ifPresent(actorRecord -> actorStore.enable(actorRecord.id()));
        }
    }

    private MemberView toView(TenantMember member) {
        UserAccount account = userAccountStore.findAccount(member.userId()).orElse(null);
        return new MemberView(member.id(), member.userId(), member.personId(),
                account == null ? null : account.email(),
                account == null ? null : account.displayName(),
                member.status().wire(), member.owner(), member.admin(), member.mfaRequired());
    }
}
