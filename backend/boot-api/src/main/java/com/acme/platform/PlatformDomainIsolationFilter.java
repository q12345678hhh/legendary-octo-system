package com.acme.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 平台域与租户域请求头互斥。
 *
 * <p>平台运营默认不能读取租户业务正文：{@code /platform/**} 只接受平台运营标识，
 * 且禁止携带 {@code X-Tenant-Id}；{@code /api/**} 反过来禁止平台运营标识。
 * 这样即使平台运营拿到租户 ID，也不能直接把平台身份带进租户业务接口。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class PlatformDomainIsolationFilter extends OncePerRequestFilter {

    static final String OPERATOR_HEADER = "X-Platform-Operator-Id";
    static final String TENANT_HEADER = "X-Tenant-Id";

    private static final Pattern OPERATOR = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

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

        String operator = trimmed(request.getHeader(OPERATOR_HEADER));
        String tenant = trimmed(request.getHeader(TENANT_HEADER));

        if (platformPath) {
            if (operator == null) {
                FilterProblemWriter.write(response, 401, "PLATFORM_OPERATOR_REQUIRED",
                        "平台接口必须携带 " + OPERATOR_HEADER);
                return;
            }
            if (!OPERATOR.matcher(operator).matches()) {
                FilterProblemWriter.write(response, 400, "PLATFORM_OPERATOR_INVALID",
                        "平台运营标识格式不合法");
                return;
            }
            if (tenant != null) {
                FilterProblemWriter.write(response, 403, "PLATFORM_TENANT_CONTEXT_FORBIDDEN",
                        "平台接口不得携带 X-Tenant-Id：租户上下文只能由服务端按目标租户建立");
                return;
            }
        } else if (operator != null) {
            FilterProblemWriter.write(response, 403, "PLATFORM_OPERATOR_NOT_ALLOWED",
                    "租户接口不接受平台运营标识；支持访问必须走工单审批后的只读通道");
            return;
        }

        chain.doFilter(request, response);
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
