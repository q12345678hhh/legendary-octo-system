package com.acme.platform;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.tenancy.TenantExecutionGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 冻结与注销冷静期的业务写阻断。
 *
 * <p>非安全方法一律拒绝，只放行注销撤回与申诉；受控导出是 GET，不受影响。
 * 插件与 AI 的执行入口还必须各自调用 {@link TenantExecutionGate}，不能只依赖本过滤器。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class TenantWriteBlockFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED = Set.of(
            "/api/v1/tenant/closing/cancel",
            "/api/v1/tenant/appeals");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final ObjectProvider<TenantExecutionGate> gateProvider;

    TenantWriteBlockFilter(ObjectProvider<TenantExecutionGate> gateProvider) {
        this.gateProvider = gateProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod())
                || !request.getRequestURI().startsWith("/api/")
                || ALLOWED.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        TenantExecutionGate gate = gateProvider.getIfAvailable();
        RequestContext context = RequestContextHolder.find().orElse(null);
        if (gate == null || context == null || context.tenantId() == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            gate.assertBusinessWrite(context);
        } catch (ApiException blocked) {
            FilterProblemWriter.write(response, blocked);
            return;
        }
        chain.doFilter(request, response);
    }
}
