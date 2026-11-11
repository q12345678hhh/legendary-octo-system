package com.acme.platform;

import com.acme.platform.identity.IdentityGuard;
import com.acme.platform.identity.IdentityProvider;
import com.acme.platform.identity.IdentityProviderService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OIDC/SAML/SCIM 预留配置。P0 只允许 draft/disabled，任何 active 请求返回 409。
 */
@RestController
@RequestMapping("/api/v1/identity-providers")
public class IdentityProviderController {

    private final IdentityProviderService identityProviderService;
    private final IdentityGuard guard;

    public IdentityProviderController(IdentityProviderService identityProviderService,
                                      IdentityGuard guard) {
        this.identityProviderService = identityProviderService;
        this.guard = guard;
    }

    public record CreateProviderRequest(String providerType, String name,
                                        Map<String, Object> config, String status) {
    }

    public record UpdateProviderRequest(Map<String, Object> config, String status) {
    }

    @GetMapping
    public List<IdentityProvider> list() {
        guard.requireAdmin(true);
        return identityProviderService.list();
    }

    @PostMapping
    public IdentityProvider create(@RequestBody CreateProviderRequest request) {
        guard.requireAdmin(true);
        return identityProviderService.create(new IdentityProviderService.CreateCommand(
                request.providerType(), request.name(), request.config(), request.status()),
                guard.requireActor());
    }

    @PatchMapping("/{providerId}")
    public IdentityProvider update(@PathVariable UUID providerId,
                                   @RequestBody UpdateProviderRequest request) {
        guard.requireAdmin(true);
        return identityProviderService.update(providerId,
                new IdentityProviderService.UpdateCommand(request.config(), request.status()));
    }
}
