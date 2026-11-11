package com.acme.platform;

import com.acme.platform.identity.AccountService;
import com.acme.platform.identity.AuthenticationService;
import com.acme.platform.identity.IdentityGuard;
import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.InvitationService;
import com.acme.platform.identity.MfaService;
import com.acme.platform.identity.SessionPrincipal;
import com.acme.platform.identity.SessionRecord;
import com.acme.platform.identity.SessionService;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证入口：登录、MFA 验证、邀请接受、登出、当前主体、改密、会话与 MFA 管理。
 *
 * <p>登录/验证成功同时下发会话 Cookie 与 CSRF Cookie；登出、改密后清除。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final SessionService sessionService;
    private final MfaService mfaService;
    private final AccountService accountService;
    private final InvitationService invitationService;
    private final IdentityGuard guard;
    private final IdentityProperties properties;

    public AuthController(AuthenticationService authenticationService,
                          SessionService sessionService,
                          MfaService mfaService,
                          AccountService accountService,
                          InvitationService invitationService,
                          IdentityGuard guard,
                          IdentityProperties properties) {
        this.authenticationService = authenticationService;
        this.sessionService = sessionService;
        this.mfaService = mfaService;
        this.accountService = accountService;
        this.invitationService = invitationService;
        this.guard = guard;
        this.properties = properties;
    }

    public record LoginRequest(String email, String password) {
    }

    public record LoginResponse(String status,
                                SessionPrincipal principal,
                                String mfaToken,
                                OffsetDateTime mfaExpiresAt,
                                OffsetDateTime sessionExpiresAt) {
    }

    public record MfaVerifyRequest(String mfaToken, String code) {
    }

    public record AcceptInvitationRequest(UUID tenantId, String activationToken, String password) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    public record ConfirmMfaRequest(String code) {
    }

    public record SessionView(UUID id,
                              UUID deviceId,
                              String deviceLabel,
                              String ip,
                              String userAgent,
                              OffsetDateTime createdAt,
                              OffsetDateTime lastSeenAt,
                              OffsetDateTime expiresAt,
                              boolean current) {
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        UUID tenantId = requireTenant();
        AuthenticationService.LoginResult result = authenticationService.login(tenantId,
                new AuthenticationService.LoginCommand(request.email(), request.password()),
                device(httpRequest));
        return respond(result, httpResponse);
    }

    @PostMapping("/mfa/verify")
    public ResponseEntity<LoginResponse> verifyMfa(@RequestBody MfaVerifyRequest request,
                                                   HttpServletRequest httpRequest,
                                                   HttpServletResponse httpResponse) {
        UUID tenantId = requireTenant();
        AuthenticationService.LoginResult result = authenticationService.verifyMfa(tenantId,
                new AuthenticationService.MfaVerifyCommand(request.mfaToken(), request.code()),
                device(httpRequest));
        return respond(result, httpResponse);
    }

    @PostMapping("/invitations/accept")
    public ResponseEntity<LoginResponse> acceptInvitation(@RequestBody AcceptInvitationRequest request,
                                                          HttpServletRequest httpRequest,
                                                          HttpServletResponse httpResponse) {
        if (request.tenantId() == null) {
            throw ApiException.badRequest("TENANT_REQUIRED", "缺少 tenantId");
        }
        InvitationService.AcceptedInvitation accepted = invitationService.accept(request.tenantId(),
                new InvitationService.AcceptInvitationCommand(request.activationToken(), request.password()),
                device(httpRequest));
        SessionCookies.writeSession(httpResponse, properties, accepted.sessionToken(),
                properties.getSessionAbsoluteTtl());
        SessionCookies.writeCsrf(httpResponse, properties, SessionCookies.newCsrfToken());
        return ResponseEntity.ok(new LoginResponse("session", accepted.principal(), null, null,
                accepted.sessionExpiresAt()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse httpResponse) {
        authenticationService.logout(guard.requireActor());
        SessionCookies.clear(httpResponse, properties);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AuthenticationService.MeView me() {
        return authenticationService.me(guard.requireActor());
    }

    @PostMapping("/password/change")
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest request,
                                               HttpServletResponse httpResponse) {
        ActorContext actor = guard.requireUser();
        accountService.changePassword(
                new AccountService.ChangePasswordCommand(request.currentPassword(), request.newPassword()),
                actor);
        SessionCookies.clear(httpResponse, properties);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    public List<SessionView> sessions() {
        ActorContext actor = guard.requireUser();
        return sessionService.list(actor.userId()).stream()
                .map(session -> toView(session, actor.sessionId()))
                .toList();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(@PathVariable UUID sessionId) {
        ActorContext actor = guard.requireUser();
        sessionService.revokeOwned(sessionId, actor.userId(), actor.actorId().toString(), "user_revoke");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/revoke-all")
    public ResponseEntity<Void> revokeAllSessions(HttpServletResponse httpResponse) {
        ActorContext actor = guard.requireUser();
        sessionService.revokeAll(actor.userId(), actor.actorId().toString(), "revoke_all");
        SessionCookies.clear(httpResponse, properties);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mfa")
    public MfaService.MfaStatusView mfaStatus() {
        ActorContext actor = guard.requireUser();
        return mfaService.status(actor.userId());
    }

    @PostMapping("/mfa/totp/start")
    public MfaService.Enrollment startMfa() {
        ActorContext actor = guard.requireUser();
        AuthenticationService.MeView me = authenticationService.me(actor);
        String email = me.email() == null ? "user-" + actor.userId() : me.email();
        return mfaService.start(actor.userId(), email);
    }

    @PostMapping("/mfa/totp/confirm")
    public List<String> confirmMfa(@RequestBody ConfirmMfaRequest request) {
        ActorContext actor = guard.requireUser();
        List<String> recoveryCodes = mfaService.confirm(actor.userId(), request.code());
        sessionService.markMfaSatisfied(actor.sessionId());
        return recoveryCodes;
    }

    @PostMapping("/mfa/disable")
    public ResponseEntity<Void> disableMfa() {
        ActorContext actor = guard.requireUser();
        if (!actor.mfaSatisfied()) {
            throw ApiException.forbidden("MFA_REQUIRED", "关闭 MFA 前必须先完成一次 MFA 验证");
        }
        mfaService.disable(actor.userId());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<LoginResponse> respond(AuthenticationService.LoginResult result,
                                                  HttpServletResponse httpResponse) {
        if (!"session".equals(result.status())) {
            return ResponseEntity.status(202).body(new LoginResponse(result.status(), null,
                    result.mfaToken(), result.mfaExpiresAt(), null));
        }
        SessionCookies.writeSession(httpResponse, properties, result.sessionToken(),
                properties.getSessionAbsoluteTtl());
        SessionCookies.writeCsrf(httpResponse, properties, SessionCookies.newCsrfToken());
        return ResponseEntity.ok(new LoginResponse("session", result.principal(), null, null,
                result.sessionExpiresAt()));
    }

    private SessionView toView(SessionRecord session, UUID currentSessionId) {
        return new SessionView(session.id(), session.deviceId(), session.deviceLabel(), session.ip(),
                session.userAgent(), session.createdAt(), session.lastSeenAt(), session.expiresAt(),
                session.id().equals(currentSessionId));
    }

    private UUID requireTenant() {
        RequestContext context = RequestContextHolder.find().orElseThrow(() ->
                ApiException.badRequest("TENANT_REQUIRED", "缺少请求上下文"));
        if (context.tenantId() == null) {
            throw ApiException.badRequest("TENANT_REQUIRED",
                    "请求必须携带 " + RequestContextBindingFilter.TENANT_HEADER);
        }
        return context.tenantId();
    }

    static SessionService.DeviceInfo device(HttpServletRequest request) {
        String deviceIdHeader = request.getHeader("X-Device-Id");
        UUID deviceId = null;
        if (deviceIdHeader != null && !deviceIdHeader.isBlank()) {
            try {
                deviceId = UUID.fromString(deviceIdHeader.trim());
            } catch (IllegalArgumentException ignored) {
                deviceId = null;
            }
        }
        String deviceLabel = request.getHeader("X-Device-Label");
        if (deviceLabel != null) {
            deviceLabel = deviceLabel.trim();
            deviceLabel = deviceLabel.length() > 100 ? deviceLabel.substring(0, 100) : deviceLabel;
        }
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 400) {
            userAgent = userAgent.substring(0, 400);
        }
        return new SessionService.DeviceInfo(deviceId, deviceLabel, request.getRemoteAddr(), userAgent);
    }
}
