package com.acme.platform;

import com.acme.platform.platform.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 把所有错误收敛成 RFC 9457 Problem Details，避免前端为每个接口写一套错误解析。
 *
 * <p>5xx 只返回通用描述，不把异常消息、SQL 或堆栈带给调用方；详细原因留在日志里，
 * 用同一行的 traceId 关联。
 */
@RestControllerAdvice
public class ProblemDetailsAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    private final ProblemDetailsSupport support;

    public ProblemDetailsAdvice(ProblemDetailsSupport support) {
        this.support = support;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> apiException(ApiException ex) {
        return respond(ex.status(), support.of(ex.status(), ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("field", error.getField());
            item.put("message", error.getDefaultMessage() == null ? "不合法" : error.getDefaultMessage());
            errors.add(item);
        }
        ProblemDetail problem = support.of(400, "VALIDATION_FAILED", "请求参数校验失败");
        problem.setProperty("errors", errors);
        return respond(400, problem);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ProblemDetail> unreadable(Exception ex) {
        return respond(400, support.of(400, "REQUEST_MALFORMED", "请求体或请求头格式不正确"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return respond(405, support.of(405, "METHOD_NOT_ALLOWED", "该资源不支持 " + ex.getMethod() + " 方法"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(NoResourceFoundException ex) {
        return respond(404, support.of(404, "RESOURCE_NOT_FOUND", "请求的资源不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception ex) {
        log.error("未处理异常：{}", ex.getClass().getSimpleName(), ex);
        return respond(500, support.of(500, "INTERNAL_ERROR", "服务内部错误，请联系管理员并提供 traceId"));
    }

    private static ResponseEntity<ProblemDetail> respond(int status, ProblemDetail problem) {
        return ResponseEntity.status(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(problem);
    }
}
