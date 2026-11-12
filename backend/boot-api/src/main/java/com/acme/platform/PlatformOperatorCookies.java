package com.acme.platform;

import com.acme.platform.identity.operator.PlatformOpsProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 平台运营 Cookie 的读写。
 *
 * <p>与租户会话的关键区别是 {@code Path=/platform}：浏览器不会把它带到 {@code /api/**}，
 * 租户会话也不会被带到平台域。会话 Cookie 为 HttpOnly，CSRF Cookie 由前端读取后双提交。
 */
final class PlatformOperatorCookies {

    static final String CSRF_HEADER = "X-CSRF-Token";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private PlatformOperatorCookies() {
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

    static void writeSession(HttpServletResponse response, PlatformOpsProperties properties,
                             String token, Duration ttl) {
        response.addHeader("Set-Cookie", build(properties.getCookieName(), token, ttl,
                properties.isCookieSecure(), true, properties.getCookiePath()));
    }

    static void writeCsrf(HttpServletResponse response, PlatformOpsProperties properties,
                          String csrf) {
        response.addHeader("Set-Cookie", build(properties.getCsrfCookieName(), csrf,
                properties.getSessionAbsoluteTtl(), properties.isCookieSecure(), false,
                properties.getCookiePath()));
    }

    static void clear(HttpServletResponse response, PlatformOpsProperties properties) {
        response.addHeader("Set-Cookie", build(properties.getCookieName(), "", Duration.ZERO,
                properties.isCookieSecure(), true, properties.getCookiePath()));
        response.addHeader("Set-Cookie", build(properties.getCsrfCookieName(), "", Duration.ZERO,
                properties.isCookieSecure(), false, properties.getCookiePath()));
    }

    private static String build(String name, String value, Duration ttl, boolean secure,
                                boolean httpOnly, String path) {
        StringBuilder builder = new StringBuilder()
                .append(name).append('=').append(value)
                .append("; Path=").append(path)
                .append("; Max-Age=").append(Math.max(0, ttl.toSeconds()))
                .append("; SameSite=Strict");
        if (httpOnly) {
            builder.append("; HttpOnly");
        }
        if (secure) {
            builder.append("; Secure");
        }
        return builder.toString();
    }
}
