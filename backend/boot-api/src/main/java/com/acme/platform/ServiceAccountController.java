package com.acme.platform;

import com.acme.platform.identity.IdentityGuard;
import com.acme.platform.identity.ServiceAccount;
import com.acme.platform.identity.ServiceAccountService;
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
 * 服务账号与 API 令牌。创建服务账号或令牌属于高权限动作，要求已完成 MFA。
 */
@RestController
@RequestMapping("/api/v1/service-accounts")
public class ServiceAccountController {

    private final ServiceAccountService serviceAccountService;
    private final IdentityGuard guard;

    public ServiceAccountController(ServiceAccountService serviceAccountService, IdentityGuard guard) {
        this.serviceAccountService = serviceAccountService;
        this.guard = guard;
    }

    public record CreateAccountRequest(String name, String description) {
    }

    public record CreateTokenRequest(String name, List<String> scopes, Integer ttlDays) {
    }

    @GetMapping
    public List<ServiceAccount> list() {
        guard.requireAdmin(false);
        return serviceAccountService.list();
    }

    @PostMapping
    public ServiceAccount create(@RequestBody CreateAccountRequest request) {
        guard.requireAdmin(true);
        return serviceAccountService.create(
                new ServiceAccountService.CreateServiceAccountCommand(request.name(), request.description()),
                guard.requireActor());
    }

    @DeleteMapping("/{serviceAccountId}")
    public ResponseEntity<Void> disable(@PathVariable UUID serviceAccountId) {
        guard.requireAdmin(true);
        serviceAccountService.disable(serviceAccountId, guard.requireActor());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{serviceAccountId}/tokens")
    public List<ServiceAccountService.TokenView> listTokens(@PathVariable UUID serviceAccountId) {
        guard.requireAdmin(false);
        return serviceAccountService.listTokens(serviceAccountId);
    }

    @PostMapping("/{serviceAccountId}/tokens")
    public ServiceAccountService.IssuedTokenView createToken(@PathVariable UUID serviceAccountId,
                                                             @RequestBody CreateTokenRequest request) {
        guard.requireAdmin(true);
        return serviceAccountService.createToken(serviceAccountId,
                new ServiceAccountService.CreateTokenCommand(request.name(), request.scopes(),
                        request.ttlDays()),
                guard.requireActor());
    }

    @DeleteMapping("/{serviceAccountId}/tokens/{tokenId}")
    public ResponseEntity<Void> revokeToken(@PathVariable UUID serviceAccountId,
                                            @PathVariable UUID tokenId) {
        guard.requireAdmin(true);
        serviceAccountService.revokeToken(serviceAccountId, tokenId, guard.requireActor());
        return ResponseEntity.noContent().build();
    }
}
