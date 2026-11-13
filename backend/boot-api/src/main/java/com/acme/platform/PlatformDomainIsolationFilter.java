package com.acme.platform;

import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.operator.PlatformOpsProperties;
import com.acme.platform.platform.security.PlatformSecurityRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 平台域与租户域请求头/Cookie 互斥。
 *
 * <p>WP02-07 起 {@code X-Platform-Operator-Id} 已退役：平台身份只能由
 * {@link PlatformOperatorAuthenticationFilter} 通过独立会话建立。任何仍携带旧头的请求
 * 都明确失败，避免旧脚本在「看起来还能用」的错觉下继续运行。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 12)
class PlatformDomainIsolationFilter extends OncePerRequestFilter {

    static final String RETIRED_OPERATOR_HEADER = "X-Platform-Operator-Id";
    static final String TENANT_HEADER = "X-Tenant-Id";

    private final IdentityProperties identityProperties;
    private final PlatformOpsProperties opsProperties;
    private final ObjectProvider<PlatformSecurityRecorder> securityRecorderProvider;

    PlatformDomainIsolationFilter(IdentityProperties identityProperties,
                                  ObjectProvider<PlatformOpsProperties> opsPropertiesProvider,
                                  ObjectProvider<PlatformSecurityRecorder> securityRecorderProvider) {
        this.identityProperties = identityProperties;
        this.opsProperties = opsPropertiesProvider.getIfAvailable(PlatformOpsProperties::new);
        this.securityRecorderProvider = securityRecorderProvider;
    }

    private void recordBlocked(String path, String code, HttpServletRequest request) {
        PlatformSecurityRecorder recorder = securityRecorderProvider.getIfAvailable();
        if (recorder != null) {
            recorder.crossTenantBlocked(path, code, request.getRemoteAddr());
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean platformPath = path.startsWith("/platform/");
        boolean tenantPath = path.startsWith("/api/");
        if (!platformPath && !tenantPath) {
            chain.doFilter(request, response);
            return;
        }

        if (request.getHeader(RETIRED_OPERATOR_HEADER) != null) {
            recordBlocked(path, "PLATFORM_OPERATOR_HEADER_RETIRED", request);
            FilterProblemWriter.write(response, 400, "PLATFORM_OPERATOR_HEADER_RETIRED",
                    RETIRED_OPERATOR_HEADER + " 已退役：请改用 POST /platform/api/v1/auth/login 获取运营会话");
            return;
        }

        if (platformPath) {
            if (request.getHeader(TENANT_HEADER) != null) {
                recordBlocked(path, "PLATFORM_TENANT_CONTEXT_FORBIDDEN", request);
                FilterProblemWriter.write(response, 403, "PLATFORM_TENANT_CONTEXT_FORBIDDEN",
                        "平台接口不得携带 X-Tenant-Id：租户上下文只能由服务端按目标租户建立");
                return;
            }
            if (PlatformOperatorCookies.read(request, identityProperties.getCookieName()).isPresent()) {
                recordBlocked(path, "TENANT_SESSION_NOT_ALLOWED_ON_PLATFORM", request);
                FilterProblemWriter.write(response, 403, "TENANT_SESSION_NOT_ALLOWED_ON_PLATFORM",
                        "平台接口不接受租户会话；平台运营账号必须使用独立登录");
                return;
            }
        } else if (PlatformOperatorCookies.read(request, opsProperties.getCookieName()).isPresent()) {
            recordBlocked(path, "PLATFORM_OPERATOR_SESSION_NOT_ALLOWED", request);
            FilterProblemWriter.write(response, 403, "PLATFORM_OPERATOR_SESSION_NOT_ALLOWED",
                    "租户接口不接受平台运营会话；支持访问必须走工单审批后的只读通道");
            return;
        }

        chain.doFilter(request, response);
    }
}
