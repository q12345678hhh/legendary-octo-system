package com.acme.platform;

import com.acme.platform.platform.outbox.DeadLetterRecord;
import com.acme.platform.platform.outbox.DeadLetterService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 死信查询与安全回放。
 *
 * <p>回放不是「再发一次」这么简单：必须写明原因（进审计表），只能重投本租户的死信，
 * 且只把状态改回 pending，真正的投递仍由 Worker 的认领逻辑完成。
 */
@RestController
@RequestMapping("/api/v1/system/outbox/dead-letters")
class DeadLetterController {

    record ReplayRequest(String reason) {
    }

    private final DeadLetterService service;

    DeadLetterController(DeadLetterService service) {
        this.service = service;
    }

    @GetMapping
    List<DeadLetterRecord> list(@RequestParam(defaultValue = "20") int limit) {
        return service.list(limit);
    }

    @PostMapping("/{eventId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> replay(@PathVariable UUID eventId, @RequestBody ReplayRequest request) {
        service.replay(eventId, request.reason());
        return Map.of("eventId", eventId, "status", "pending");
    }
}
