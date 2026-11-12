package com.acme.platform;

import com.acme.platform.identity.operator.PlatformPermissionCatalog;
import com.acme.platform.platform.overview.PlatformOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 运营总览：租户数、活跃度、容量、错误率、AI 成本与集成健康。 */
@RestController
@RequestMapping("/platform/api/v1")
class PlatformOverviewController {

    private final PlatformOverviewService service;

    PlatformOverviewController(PlatformOverviewService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    PlatformOverviewService.Overview overview() {
        PlatformActors.require(PlatformPermissionCatalog.OVERVIEW_READ);
        return service.overview();
    }
}
