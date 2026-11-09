package com.acme.platform;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进程级存活端点。位于根包（装配层），不属于任何业务模块。
 * 契约见 contracts/openapi/platform-api.v1.json。
 */
@RestController
@RequestMapping("/api/v1/system")
class SystemHealthController {

    private final String service;

    SystemHealthController(@Value("${spring.application.name}") String service) {
        this.service = service;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP", "service", service);
    }
}
