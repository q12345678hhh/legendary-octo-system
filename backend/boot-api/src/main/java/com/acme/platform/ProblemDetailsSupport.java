package com.acme.platform;

import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 统一的 RFC 9457 Problem Details 构造器。
 *
 * <p>所有错误响应都带 {@code code}、{@code correlationId}、{@code traceId}：
 * 前端展示 code 对应的中文提示，用户报障时只需提供 traceId，就能在日志与数据库里定位。
 */
@Component
public class ProblemDetailsSupport {

    private final ObjectMapper objectMapper;

    public ProblemDetailsSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProblemDetail of(int status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatusCode.valueOf(status),
                detail == null ? "请求未能完成" : detail);
        problem.setTitle(title(status));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", OffsetDateTime.now().toString());
        RequestContext context = RequestContextHolder.current();
        if (context != null) {
            problem.setProperty("correlationId", context.correlationId());
            problem.setProperty("traceId", context.traceId());
        }
        return problem;
    }

    /** 供过滤器使用：过滤器不走异常处理器，需要自己写出 problem+json。 */
    public void write(HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        ProblemDetail problem = of(status, code, detail);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
    }

    public Map<String, Object> body(ProblemDetail problem) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", problem.getType() == null ? "about:blank" : problem.getType().toString());
        map.put("title", problem.getTitle());
        map.put("status", problem.getStatus());
        map.put("detail", problem.getDetail());
        map.putAll(problem.getProperties());
        return map;
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "请求不合法";
            case 401 -> "未认证";
            case 403 -> "无权限";
            case 404 -> "资源不存在";
            case 409 -> "状态冲突";
            case 422 -> "语义校验失败";
            case 429 -> "请求过于频繁";
            case 503 -> "服务暂不可用";
            default -> status >= 500 ? "服务内部错误" : "请求失败";
        };
    }
}
