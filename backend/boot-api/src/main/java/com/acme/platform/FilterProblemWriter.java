package com.acme.platform;

import com.acme.platform.platform.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 过滤器阶段没有 {@code @ControllerAdvice}，必须自己写 RFC 9457 响应，
 * 否则容器会把它变成 500 并丢掉稳定的错误码。
 */
final class FilterProblemWriter {

    private FilterProblemWriter() {
    }

    static void write(HttpServletResponse response, ApiException exception) throws IOException {
        write(response, exception.status(), exception.code(), exception.getMessage());
    }

    static void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\""
                + escape(code) + "\",\"status\":" + status
                + ",\"code\":\"" + escape(code)
                + "\",\"detail\":\"" + escape(detail) + "\"}");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
