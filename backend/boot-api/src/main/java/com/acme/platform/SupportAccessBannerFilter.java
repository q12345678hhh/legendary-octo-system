package com.acme.platform;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.tenancy.TenantSupportAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 支持访问的只读校验与醒目标识。
 *
 * <p>带 {@code X-Support-Access-Id} 的租户请求必须命中已批准、未撤销、未过期的授权；
 * 只允许 GET；每个响应回写工单、到期时间与“只读、全部留痕”的提示头。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
class SupportAccessBannerFilter extends OncePerRequestFilter {

    static final String ACCESS_HEADER = "X-Support-Access-Id";

    private final ObjectProvider<TenantSupportAccessService> serviceProvider;

    SupportAccessBannerFilter(ObjectProvider<TenantSupportAccessService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String rawAccessId = request.getHeader(ACCESS_HEADER);
        if (rawAccessId == null || rawAccessId.isBlank() || !request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            FilterProblemWriter.write(response, 403, "SUPPORT_ACCESS_READ_ONLY",
                    "支持访问是只读通道，不能执行写操作");
            return;
        }
        RequestContext context = RequestContextHolder.find().orElse(null);
        if (context == null || context.tenantId() == null) {
            FilterProblemWriter.write(response, 400, "TENANT_REQUIRED",
                    "支持访问必须携带 X-Tenant-Id");
            return;
        }
        TenantSupportAccessService service = serviceProvider.getIfAvailable();
        if (service == null) {
            chain.doFilter(request, response);
            return;
        }
        UUID accessId;
        try {
            accessId = UUID.fromString(rawAccessId.trim());
        } catch (IllegalArgumentException invalid) {
            FilterProblemWriter.write(response, 400, "SUPPORT_ACCESS_INVALID", "支持访问标识不是合法 UUID");
            return;
        }
        try {
            TenantSupportAccessService.SupportGrant grant = service.enter(context.tenantId(), accessId);
            // 响应头只能是 ASCII，中文提示放在 /support-access/.../enter 的 banner 字段里。
            response.setHeader("X-Support-Access-Notice",
                    "Support access active; ticket=" + grant.ticketId() + "; read-only; all access audited");
            response.setHeader("X-Support-Access-Expires-At", grant.expiresAt().toString());
            response.setHeader("X-Support-Access-Ticket", grant.ticketId());
            MDC.put("support_access_id", accessId.toString());
            try {
                chain.doFilter(request, response);
            } finally {
                MDC.remove("support_access_id");
            }
        } catch (ApiException denied) {
            FilterProblemWriter.write(response, denied);
        }
    }
}
