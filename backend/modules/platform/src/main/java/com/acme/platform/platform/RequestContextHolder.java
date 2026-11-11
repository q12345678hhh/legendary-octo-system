package com.acme.platform.platform;

import java.util.Optional;

/**
 * 请求上下文持有者：绑定当前线程，请求结束后必须调用 {@link #clear()}。
 *
 * <p>用 ThreadLocal 而不是参数透传，是为了让 outbox 写入这类基础设施能力
 * 不必把租户与关联 ID 一层层塞进业务方法签名。
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private RequestContextHolder() {
    }

    public static void set(RequestContext context) {
        CURRENT.set(context);
    }

    public static Optional<RequestContext> find() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static RequestContext current() {
        RequestContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("当前线程没有请求上下文：事件必须在受管请求内写入");
        }
        return context;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
