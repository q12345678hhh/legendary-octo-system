package com.acme.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 模块边界校验：跨模块只能经由公开应用服务或事件，禁止读取他模块内部包。
 * 新增、删除或重命名模块必须同步修改本测试，避免边界悄然漂移。
 */
class ModuleStructureTest {

    private static final List<String> EXPECTED_MODULES = List.of(
            "tenancy",
            "identity",
            "organization",
            "authorization",
            "department",
            "appcatalog",
            "lowcode",
            "workflow",
            "hr",
            "worklog",
            "collaboration",
            "integration",
            "plugin",
            "ai",
            "platform");

    private static final ApplicationModules MODULES = ApplicationModules.of(PlatformApiApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        MODULES.verify();
    }

    @Test
    void exactlyTheFifteenBusinessModulesAreDetected() {
        List<String> detected = MODULES.stream().map(module -> module.getName()).sorted().toList();

        assertThat(detected).containsExactlyElementsOf(EXPECTED_MODULES.stream().sorted().toList());
    }
}
