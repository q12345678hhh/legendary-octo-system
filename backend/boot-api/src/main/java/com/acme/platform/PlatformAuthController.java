package com.acme.platform;

import com.acme.platform.identity.operator.PlatformActorContext;
import com.acme.platform.identity.operator.PlatformOpsProperties;
import com.acme.platform.identity.operator.PlatformOperatorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台运营认证入口：独立于 {@code /api/v1/auth}，不接受租户头。
 *
 * <p>登录成功同时下发运营会话与 CSRF Cookie；两者都只挂在 {@code Path=/platform}。
 */
@RestController
@RequestMapping("/platform/api/v1/auth")
public class PlatformAuthController {

    public record LoginRequest(String email, String password, String totpCode) {
    }

    public record LoginResponse(PlatformOperatorService.MeView operator, String sessionExpiresAt) {
    }

    public record ConfirmMfaRequest(String code) {
    }

    private final PlatformOperatorService service;
    private final PlatformOpsProperties properties;

    public PlatformAuthController(PlatformOperatorService service, PlatformOpsProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        PlatformOperatorService.LoginResult result = service.login(request.email(),
                request.password(), request.totpCode(), httpRequest.getRemoteAddr(),
                userAgent(httpRequest));
        PlatformOperatorCookies.writeSession(httpResponse, properties, result.sessionToken(),
                properties.getSessionAbsoluteTtl());
        PlatformOperatorCookies.writeCsrf(httpResponse, properties,
                PlatformOperatorCookies.newCsrfToken());
        return ResponseEntity.ok(new LoginResponse(service.me(result.actor()),
                result.expiresAt().toString()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse httpResponse) {
        service.logout(PlatformActors.require());
        PlatformOperatorCookies.clear(httpResponse, properties);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public PlatformOperatorService.MeView me() {
        return service.me(PlatformActors.require());
    }

    @PostMapping("/mfa/start")
    public PlatformOperatorService.MfaEnrollment startMfa() {
        PlatformActorContext actor = PlatformActors.require();
        return service.startMfa(actor);
    }

    @PostMapping("/mfa/confirm")
    public ResponseEntity<Void> confirmMfa(@RequestBody ConfirmMfaRequest request) {
        service.confirmMfa(PlatformActors.require(), request.code());
        return ResponseEntity.noContent().build();
    }

    private static String userAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        return userAgent == null || userAgent.length() <= 400 ? userAgent : userAgent.substring(0, 400);
    }
}
