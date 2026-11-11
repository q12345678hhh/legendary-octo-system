package com.acme.platform;

import com.acme.platform.identity.IdentityProperties;
import com.acme.platform.identity.SessionPrincipal;
import com.acme.platform.identity.SessionService;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ActorContextHolder;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 真实认证：用会话 Cookie 或服务账号 Bearer 令牌解析认证主体。
 *
 * <p>只作用于 {@code /api/**}；平台域 {@code /platform/**} 仍由平台运营标识保护，
 * 独立认证域属于 WP02-07。认证成功后会重建 {@link RequestContext}（subjectId=actorId）
 * 并写入 {@link ActorContextHolder}，供业务服务做授权与审计。
 *
 * <p>Cookie 认证的写操作必须双提交 CSRF 令牌；Bearer 令牌不依赖 Cookie，因此跳过。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
class AuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_POST_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/mfa/verify",
            "/api/v1/auth/invitations/accept");

    private static final String PUBLIC_HEALTH_PATH = "/api/v1/system/health";

    private final SessionService sessionService;
    private final IdentityProperties properties;

    AuthenticationFilter(SessionService sessionService, IdentityProperties properties) {
        this.sessionService = sessionService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || isPublic(request, path)) {
            chain.doFilter(request, response);
            return;
        }

        RequestContext context = RequestContextHolder.find().orElse(null);
        UUID tenantId = context == null ? null : context.tenantId();
        if (tenantId == null) {
            FilterProblemWriter.write(response, 401, "TENANT_REQUIRED",
                    "请求必须携带 " + RequestContextBindingFilter.TENANT_HEADER);
            return;
        }

        try {
            SessionPrincipal principal = authenticate(request, response, tenantId);
            ActorContextHolder.set(new ActorContext(tenantId, principal.actorId(), principal.userId(),
                    principal.memberId(), principal.actorType(), principal.mfaSatisfied(),
                    principal.sessionId()));
            RequestContextHolder.set(new RequestContext(tenantId, principal.actorId().toString(),
                    context.correlationId(), context.traceId()));
            MDC.put("subject_id", principal.actorId().toString());
            try {
                chain.doFilter(request, response);
            } finally {
                MDC.remove("subject_id");
                ActorContextHolder.clear();
            }
        } catch (ApiException e) {
            FilterProblemWriter.write(response, e);
        }
    }

    private SessionPrincipal authenticate(HttpServletRequest request, HttpServletResponse response,
                                          UUID tenantId) {
        String bearer = bearerToken(request);
        if (bearer != null) {
            return sessionService.authenticateToken(tenantId, bearer);
        }
        String sessionToken = SessionCookies.read(request, properties.getCookieName())
                .orElseThrow(() -> new ApiException(401, "SESSION_REQUIRED", "请求必须携带会话或 API 令牌"));
        if (!isSafeMethod(request.getMethod())) {
            String expected = SessionCookies.read(request, properties.getCsrfCookieName()).orElse(null);
            String provided = request.getHeader(SessionCookies.CSRF_HEADER);
            if (expected == null || provided == null || !expected.equals(provided)) {
                throw new ApiException(403, "CSRF_REQUIRED",
                        "写操作必须携带与 " + properties.getCsrfCookieName() + " 一致的 "
                                + SessionCookies.CSRF_HEADER);
            }
        }
        return sessionService.authenticate(tenantId, sessionToken);
    }

    private static boolean isPublic(HttpServletRequest request, String path) {
        if ("POST".equalsIgnoreCase(request.getMethod()) && PUBLIC_POST_PATHS.contains(path)) {
            return true;
        }
        return "GET".equalsIgnoreCase(request.getMethod()) && PUBLIC_HEALTH_PATH.equals(path);
    }

    private static boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
