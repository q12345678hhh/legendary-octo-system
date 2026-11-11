package com.acme.platform;

import com.acme.platform.platform.PlatformMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP 指标：只按状态码计数，不按路径打标签。
 *
 * <p>路径里可能带文件 ID、作业 ID，作为标签会让时间序列爆炸；需要按端点排障时看日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class HttpMetricsFilter extends OncePerRequestFilter {

    private final PlatformMetrics metrics;

    HttpMetricsFilter(PlatformMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            metrics.httpRequest(response.getStatus());
        }
    }
}
