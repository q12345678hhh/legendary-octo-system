package com.acme.platform;

import com.acme.platform.identity.IdentityProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 会话与 CSRF Cookie 的读写。
 *
 * <p>会话 Cookie 带 {@code HttpOnly}，脚本读不到；CSRF Cookie 故意不加 {@code HttpOnly}，
 * 由前端读出后放进 {@code X-CSRF-Token} 头，两者双提交一致才允许写操作。
 */
final class SessionCookies {

    static final String CSRF_HEADER = "X-CSRF-Token";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SessionCookies() {
    }

    static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    static String newCsrfToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    static void writeSession(HttpServletResponse response, IdentityProperties properties,
                             String token, Duration ttl) {
        response.addHeader("Set-Cookie", build(properties.getCookieName(), token, ttl,
                properties.isCookieSecure(), true, "Lax"));
    }

    static void writeCsrf(HttpServletResponse response, IdentityProperties properties, String csrf) {
        response.addHeader("Set-Cookie", build(properties.getCsrfCookieName(), csrf,
                properties.getSessionAbsoluteTtl(), properties.isCookieSecure(), false, "Lax"));
    }

    static void clear(HttpServletResponse response, IdentityProperties properties) {
        response.addHeader("Set-Cookie", build(properties.getCookieName(), "", Duration.ZERO,
                properties.isCookieSecure(), true, "Lax"));
        response.addHeader("Set-Cookie", build(properties.getCsrfCookieName(), "", Duration.ZERO,
                properties.isCookieSecure(), false, "Lax"));
    }

    private static String build(String name, String value, Duration ttl, boolean secure,
                                boolean httpOnly, String sameSite) {
        StringBuilder builder = new StringBuilder()
                .append(name).append('=').append(value)
                .append("; Path=/")
                .append("; Max-Age=").append(Math.max(0, ttl.toSeconds()))
                .append("; SameSite=").append(sameSite);
        if (httpOnly) {
            builder.append("; HttpOnly");
        }
        if (secure) {
            builder.append("; Secure");
        }
        return builder.toString();
    }
}
