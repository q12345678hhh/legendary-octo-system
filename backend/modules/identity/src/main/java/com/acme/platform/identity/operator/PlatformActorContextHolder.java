package com.acme.platform.identity.operator;

import java.util.Optional;

/** 平台运营主体的请求级持有者；只由平台认证过滤器写入与清理。 */
public final class PlatformActorContextHolder {

    private static final ThreadLocal<PlatformActorContext> CURRENT = new ThreadLocal<>();

    private PlatformActorContextHolder() {
    }

    public static void set(PlatformActorContext context) {
        CURRENT.set(context);
    }

    public static Optional<PlatformActorContext> find() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static PlatformActorContext current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
