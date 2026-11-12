package com.acme.platform;

import com.acme.platform.identity.operator.PlatformActorContext;
import com.acme.platform.identity.operator.PlatformActorContextHolder;
import com.acme.platform.identity.operator.PlatformOpsProperties;
import com.acme.platform.identity.operator.PlatformOperatorService;
import com.acme.platform.platform.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 平台运营独立认证：只作用于 {@code /platform/api/v1/**}。
 *
 * <p>租户会话 Cookie 与平台 Cookie 的 Path 不同，浏览器本不会混发；这里再显式拒绝
 * 平台路径上出现租户会话，避免代理或手工构造 Cookie 时串域。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 16)
class PlatformOperatorAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/platform/api/";
    private static final Set<String> PUBLIC_POST_PATHS = Set.of("/platform/api/v1/auth/login");

    private final ObjectProvider<PlatformOperatorService> operatorServiceProvider;
    private final PlatformOpsProperties properties;

    PlatformOperatorAuthenticationFilter(
            ObjectProvider<PlatformOperatorService> operatorServiceProvider,
            ObjectProvider<PlatformOpsProperties> propertiesProvider) {
        this.operatorServiceProvider = operatorServiceProvider;
        this.properties = propertiesProvider.getIfAvailable(PlatformOpsProperties::new);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        if ("POST".equalsIgnoreCase(request.getMethod()) && PUBLIC_POST_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            PlatformOperatorService operatorService = operatorServiceProvider.getIfAvailable();
            if (operatorService == null) {
                throw new ApiException(503, "PLATFORM_AUTH_UNAVAILABLE",
                        "平台认证组件未装配，拒绝所有平台请求");
            }
            String token = PlatformOperatorCookies.read(request, properties.getCookieName())
                    .orElseThrow(() -> new ApiException(401, "PLATFORM_SESSION_REQUIRED",
                            "平台接口必须使用运营账号登录"));
            if (!isSafeMethod(request.getMethod()) && !csrfValid(request)) {
                throw new ApiException(403, "PLATFORM_CSRF_REQUIRED",
                        "平台写操作必须携带与 " + properties.getCsrfCookieName()
                                + " 一致的 " + PlatformOperatorCookies.CSRF_HEADER);
            }
            PlatformActorContext actor = operatorService.authenticate(token);
            PlatformActorContextHolder.set(actor);
            MDC.put("platform_operator_id", actor.operatorId().toString());
            try {
                chain.doFilter(request, response);
            } finally {
                MDC.remove("platform_operator_id");
                PlatformActorContextHolder.clear();
            }
        } catch (ApiException e) {
            FilterProblemWriter.write(response, e);
        }
    }

    private boolean csrfValid(HttpServletRequest request) {
        String expected = PlatformOperatorCookies.read(request, properties.getCsrfCookieName())
                .orElse(null);
        String provided = request.getHeader(PlatformOperatorCookies.CSRF_HEADER);
        return expected != null && provided != null && expected.equals(provided);
    }

    private static boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }
}
