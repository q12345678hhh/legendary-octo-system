package com.acme.platform;

import com.acme.platform.identity.operator.PlatformPermissionCatalog;
import com.acme.platform.platform.settings.PlatformSettingService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 平台设置：邮件、对象存储、模型网关与全局限制。
 *
 * <p>密钥类设置只回「是否已配置」，永不回显明文；变更写平台审计。
 */
@RestController
@RequestMapping("/platform/api/v1/settings")
class PlatformSettingController {

    private final PlatformSettingService service;

    PlatformSettingController(PlatformSettingService service) {
        this.service = service;
    }

    @GetMapping
    List<PlatformSettingService.SettingView> all() {
        PlatformActors.require(PlatformPermissionCatalog.SETTINGS_READ);
        return service.all();
    }

    @PutMapping("/{key}")
    PlatformSettingService.SettingView put(@PathVariable String key,
                                           @RequestBody JsonNode body) {
        JsonNode value = body == null ? null : body.get("value");
        return service.put(key, value, PlatformActors.ref(
                PlatformActors.requireManage(PlatformPermissionCatalog.SETTINGS_MANAGE)));
    }
}
