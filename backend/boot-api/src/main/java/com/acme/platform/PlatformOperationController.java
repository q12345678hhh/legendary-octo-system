package com.acme.platform;

import com.acme.platform.identity.operator.PlatformPermissionCatalog;
import com.acme.platform.platform.operations.PlatformOperationService;
import com.acme.platform.platform.operations.PlatformOperationStore;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 作业与审计：迁移、备份、恢复演练、异步队列与平台级审计。 */
@RestController
@RequestMapping("/platform/api/v1/operations")
class PlatformOperationController {

    record RunRequest(String kind, UUID tenantId, String status, String summary) {
    }

    private final PlatformOperationService service;

    PlatformOperationController(PlatformOperationService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    PlatformOperationService.OperationsOverview overview() {
        PlatformActors.require(PlatformPermissionCatalog.OPERATIONS_READ);
        return service.overview();
    }

    @GetMapping("/runs")
    List<PlatformOperationStore.OperationRun> runs(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "50") int limit) {
        PlatformActors.require(PlatformPermissionCatalog.OPERATIONS_READ);
        return service.runs(kind, limit);
    }

    @PostMapping("/runs")
    PlatformOperationStore.OperationRun record(@RequestBody RunRequest request) {
        return service.record(request.kind(), request.tenantId(), request.status(),
                request.summary(), PlatformActors.ref(
                        PlatformActors.requireManage(PlatformPermissionCatalog.OPERATIONS_MANAGE)));
    }

    @GetMapping("/audit")
    List<PlatformOperationStore.AuditEvent> audit(@RequestParam(defaultValue = "50") int limit) {
        PlatformActors.require(PlatformPermissionCatalog.OPERATIONS_READ);
        return service.audit(limit);
    }
}
