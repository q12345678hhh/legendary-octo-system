package com.acme.platform;

import com.acme.platform.appcatalog.ModuleActivationService;
import com.acme.platform.appcatalog.ModuleRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 模块停用的运行时强制拦截。
 *
 * <p>位于认证之后：认证失败不会被本过滤器掩盖；命中已停用模块的 API 一律 403
 * {@code MODULE_DISABLED}。登录、能力清单、模块中心与健康检查放行，保证管理员可以自救。
 * 查询激活状态异常时放行并告警，避免数据库抖动演变成全站不可用。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 26)
class ModuleActivationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ModuleActivationFilter.class);

    private static final List<String> EXEMPT_PREFIXES = List.of(
            "/api/v1/auth/",
            "/api/v1/app-catalog/",
            "/api/v1/me/capabilities");

    private final ModuleActivationService activationService;
    private final boolean enforce;

    ModuleActivationFilter(ModuleActivationService activationService,
                           @Value("${platform.module.activation.enforce:true}") boolean enforce) {
        this.activationService = activationService;
        this.enforce = enforce;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!enforce || !path.startsWith("/api/") || isExempt(path)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            Optional<String> moduleCode = ModuleRegistry.moduleForPath(path);
            if (moduleCode.isEmpty()) {
                moduleCode = activationService.moduleForPath(path);
            }
            if (moduleCode.isEmpty()) {
                chain.doFilter(request, response);
                return;
            }
            Set<String> enabled = activationService.enabledModuleCodes();
            if (enabled.contains(moduleCode.get())) {
                chain.doFilter(request, response);
                return;
            }
            FilterProblemWriter.write(response, 403, "MODULE_DISABLED",
                    "模块 " + moduleCode.get() + " 已被管理员停用");
        } catch (RuntimeException unavailable) {
            log.warn("模块激活状态读取失败，放行请求 {}：{}", path, unavailable.getMessage());
            chain.doFilter(request, response);
        }
    }

    private static boolean isExempt(String path) {
        if ("/api/v1/system/health".equals(path)) {
            return true;
        }
        return EXEMPT_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
