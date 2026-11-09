package com.acme.platform.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * 测试租户标识。同一名称始终映射到同一 UUID，用于跨租户负例与幂等复跑。
 *
 * @param name 租户名称，大小写与首尾空白不参与身份判定
 */
public record TestTenant(String name) {

    public TestTenant {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tenant name must not be blank");
        }
    }

    public static TestTenant of(String name) {
        return new TestTenant(name);
    }

    public String key() {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public UUID id() {
        return UUID.nameUUIDFromBytes(("tenant:" + key()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return key();
    }
}
