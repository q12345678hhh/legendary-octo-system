package com.acme.platform.platform;

import java.util.Optional;

/**
 * 认证主体绑定的线程本地存储，生命周期严格等于一次请求。
 *
 * <p>由装配层的认证过滤器写入，finally 清理；业务模块只能读取，不能伪造。
 */
public final class ActorContextHolder {

    private static final ThreadLocal<ActorContext> CURRENT = new ThreadLocal<>();

    private ActorContextHolder() {
    }

    public static void set(ActorContext actor) {
        CURRENT.set(actor);
    }

    public static Optional<ActorContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static ActorContext require() {
        ActorContext actor = CURRENT.get();
        if (actor == null) {
            throw new IllegalStateException("当前请求没有已认证主体");
        }
        return actor;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
