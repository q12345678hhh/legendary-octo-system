package com.acme.platform;

import com.acme.platform.appcatalog.ModuleActivationService;
import com.acme.platform.authorization.AuthorizationService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前主体的能力清单：权限码 + 已启用模块，供前端隐藏导航项。
 *
 * <p>隐藏不等于允许：每个直接路由仍由后端完整授权链与模块激活过滤器重新判定。
 */
@RestController
@RequestMapping("/api/v1")
class CapabilityController {

    private final AuthorizationService authorization;
    private final ModuleActivationService moduleActivationService;
    private final boolean moduleEnforcement;

    CapabilityController(AuthorizationService authorization,
                         ModuleActivationService moduleActivationService,
                         @Value("${platform.module.activation.enforce:true}")
                         boolean moduleEnforcement) {
        this.authorization = authorization;
        this.moduleActivationService = moduleActivationService;
        this.moduleEnforcement = moduleEnforcement;
    }

    @GetMapping("/me/capabilities")
    Map<String, Object> capabilities() {
        return Map.of(
                "permissions", authorization.heldPermissions(),
                "enabledModules", moduleActivationService.enabledModuleCodes(),
                "moduleRoutes", moduleActivationService.enabledModuleRoutes(),
                "moduleEnforcement", moduleEnforcement);
    }
}
