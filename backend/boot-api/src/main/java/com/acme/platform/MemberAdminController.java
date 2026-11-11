package com.acme.platform;

import com.acme.platform.identity.AccountService;
import com.acme.platform.identity.IdentityGuard;
import com.acme.platform.identity.InvitationService;
import com.acme.platform.identity.MemberStatus;
import com.acme.platform.platform.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成员管理：列表、邀请、启停与标志、账号更换、重置密码。
 *
 * <p>全部端点要求 owner/admin；邀请、启停、换号、重置密码还要求本次会话已完成 MFA。
 */
@RestController
@RequestMapping("/api/v1/members")
public class MemberAdminController {

    private final AccountService accountService;
    private final InvitationService invitationService;
    private final IdentityGuard guard;

    public MemberAdminController(AccountService accountService,
                                 InvitationService invitationService,
                                 IdentityGuard guard) {
        this.accountService = accountService;
        this.invitationService = invitationService;
        this.guard = guard;
    }

    public record InviteRequest(String email, String displayName, UUID personId,
                                Boolean admin, Boolean mfaRequired) {
    }

    public record UpdateMemberRequest(String status, Boolean admin, Boolean mfaRequired) {
    }

    public record ChangeAccountRequest(UUID newUserId, String reason) {
    }

    @GetMapping
    public List<AccountService.MemberView> list() {
        guard.requireAdmin(false);
        return accountService.list();
    }

    @PostMapping("/invitations")
    public InvitationService.InvitationIssued invite(@RequestBody InviteRequest request) {
        guard.requireAdmin(true);
        return invitationService.create(new InvitationService.CreateInvitationCommand(
                request.email(), request.displayName(), request.personId(),
                request.admin(), request.mfaRequired()));
    }

    @PatchMapping("/{memberId}")
    public AccountService.MemberView update(@PathVariable UUID memberId,
                                            @RequestBody UpdateMemberRequest request) {
        guard.requireAdmin(true);
        MemberStatus status = null;
        if (request.status() != null && !request.status().isBlank()) {
            try {
                status = MemberStatus.fromWire(request.status().trim().toLowerCase());
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("MEMBER_STATUS_INVALID", "未知的成员状态");
            }
        }
        return accountService.update(memberId,
                new AccountService.UpdateMemberCommand(status, request.admin(), request.mfaRequired()),
                guard.requireActor());
    }

    @PatchMapping("/{memberId}/account")
    public AccountService.MemberView changeAccount(@PathVariable UUID memberId,
                                                   @RequestBody ChangeAccountRequest request) {
        guard.requireAdmin(true);
        return accountService.changeAccount(memberId,
                new AccountService.ChangeAccountCommand(request.newUserId(), request.reason()),
                guard.requireActor());
    }

    @PostMapping("/{memberId}/reset-password")
    public InvitationService.InvitationIssued resetPassword(@PathVariable UUID memberId) {
        guard.requireAdmin(true);
        return invitationService.resetPassword(memberId);
    }
}
