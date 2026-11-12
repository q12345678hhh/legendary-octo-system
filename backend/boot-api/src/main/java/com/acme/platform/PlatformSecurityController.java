package com.acme.platform;

import com.acme.platform.identity.operator.PlatformPermissionCatalog;
import com.acme.platform.platform.security.PlatformSecurityService;
import com.acme.platform.platform.security.PlatformSecurityStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 安全运营：异常登录、跨租户阻断、秘密轮换、漏洞与事件。 */
@RestController
@RequestMapping("/platform/api/v1/security")
class PlatformSecurityController {

    record EventRequest(UUID tenantId, String category, String severity, String summary,
                        Map<String, Object> detail) {
    }

    record SecretRotationRequest(String secretName, String note) {
    }

    record EventIdResponse(UUID id) {
    }

    private final PlatformSecurityService service;

    PlatformSecurityController(PlatformSecurityService service) {
        this.service = service;
    }

    @GetMapping("/events")
    List<PlatformSecurityStore.SecurityEvent> events(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        PlatformActors.require(PlatformPermissionCatalog.SECURITY_READ);
        return service.list(category, status, limit);
    }

    @PostMapping("/events")
    EventIdResponse record(@RequestBody EventRequest request) {
        UUID id = service.record(request.tenantId(), request.category(), request.severity(),
                request.summary(), request.detail(), PlatformActors.ref(
                        PlatformActors.requireManage(PlatformPermissionCatalog.SECURITY_MANAGE)));
        return new EventIdResponse(id);
    }

    @PostMapping("/events/{eventId}/acknowledge")
    PlatformSecurityStore.SecurityEvent acknowledge(@PathVariable UUID eventId) {
        return service.acknowledge(eventId, PlatformActors.ref(
                PlatformActors.requireManage(PlatformPermissionCatalog.SECURITY_MANAGE)));
    }

    @PostMapping("/events/{eventId}/resolve")
    PlatformSecurityStore.SecurityEvent resolve(@PathVariable UUID eventId) {
        return service.resolve(eventId, PlatformActors.ref(
                PlatformActors.requireManage(PlatformPermissionCatalog.SECURITY_MANAGE)));
    }

    @PostMapping("/secret-rotations")
    EventIdResponse secretRotation(@RequestBody SecretRotationRequest request) {
        UUID id = service.recordSecretRotation(request.secretName(), request.note(),
                PlatformActors.ref(
                        PlatformActors.requireManage(PlatformPermissionCatalog.SECURITY_MANAGE)));
        return new EventIdResponse(id);
    }
}
