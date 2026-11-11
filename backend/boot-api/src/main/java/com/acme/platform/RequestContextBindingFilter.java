package com.acme.platform;

import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 统一请求上下文：解析或生成关联 ID 与 trace ID、绑定租户，并回写到响应头。
 *
 * <p>命名为 Binding 是为了与 Spring 自带的 {@code RequestContextFilter} 区分：那个负责暴露
 * Spring 自己的 RequestContextHolder，本类负责绑定平台租户与关联 ID。
 *
 * <p>位于根包（装配层）：它服务于所有模块，不属于任何单个业务模块。
 * 上下文的生命周期严格等于本次请求，结束后在线程被复用前清理。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestContextBindingFilter extends OncePerRequestFilter {

    static final String CORRELATION_HEADER = "X-Correlation-Id";
    static final String TRACE_HEADER = "X-Trace-Id";
    static final String TENANT_HEADER = "X-Tenant-Id";

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = header(request, CORRELATION_HEADER, () -> UUID.randomUUID().toString());
        String traceId = header(request, TRACE_HEADER, RequestContextBindingFilter::newTraceId);
        if (!TRACE_ID.matcher(traceId).matches()) {
            traceId = newTraceId();
        }
        UUID tenantId = tenantId(request);

        RequestContextHolder.set(new RequestContext(tenantId, correlationId, traceId));
        MDC.put("correlation_id", correlationId);
        MDC.put("trace_id", traceId);
        if (tenantId != null) {
            MDC.put("tenant_id", tenantId.toString());
        }
        response.setHeader(CORRELATION_HEADER, correlationId);
        response.setHeader(TRACE_HEADER, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlation_id");
            MDC.remove("trace_id");
            MDC.remove("tenant_id");
            RequestContextHolder.clear();
        }
    }

    private static String header(HttpServletRequest request, String name, Supplier<String> fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback.get() : value.trim();
    }

    private UUID tenantId(HttpServletRequest request) {
        String raw = request.getHeader(TENANT_HEADER);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            logger.warn("忽略非法租户头 " + TENANT_HEADER + "=" + raw);
            return null;
        }
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
