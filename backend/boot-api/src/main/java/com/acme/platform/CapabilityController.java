package com.acme.platform;

import com.acme.platform.authorization.AuthorizationService;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前主体的能力清单：只返回权限码，供前端隐藏导航项。
 *
 * <p>隐藏不等于允许：每个直接路由仍由后端完整授权链重新判定。
 */
@RestController
@RequestMapping("/api/v1")
class CapabilityController {

    private final AuthorizationService authorization;

    CapabilityController(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @GetMapping("/me/capabilities")
    Map<String, Set<String>> capabilities() {
        return Map.of("permissions", authorization.heldPermissions());
    }
}
