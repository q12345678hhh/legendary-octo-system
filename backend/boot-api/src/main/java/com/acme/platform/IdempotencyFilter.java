package com.acme.platform;

import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.idempotency.IdempotencyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 写请求幂等过滤器：带 {@code Idempotency-Key} 的变更请求只会产生一次副作用。
 *
 * <p>执行顺序很重要：必须在本类之前完成租户与主体解析（RequestContextBindingFilter），
 * 因为幂等键的归属是「租户 + 主体 + 端点」。
 *
 * <p>缓存的响应最多 2MB；超过上限的请求体直接拒绝，避免把大文件塞进数据库。
 * 5xx 不缓存：服务端错误没有可复现的结果，删掉占位让调用方能重试。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final IdempotencyService service;
    private final ProblemDetailsSupport problems;
    private final PlatformMetrics metrics;

    public IdempotencyFilter(IdempotencyService service, ProblemDetailsSupport problems,
                             PlatformMetrics metrics) {
        this.service = service;
        this.problems = problems;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/")
                || !MUTATING.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        if (!KEY_PATTERN.matcher(key.trim()).matches()) {
            metrics.idempotency("invalid");
            problems.write(response, 400, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key 必须是 8-128 位字母、数字或 . _ : -");
            return;
        }

        RequestContext context = RequestContextHolder.current();
        if (context == null || context.tenantId() == null || context.subjectId() == null) {
            metrics.idempotency("missing_identity");
            problems.write(response, 400, "IDENTITY_REQUIRED",
                    "使用 Idempotency-Key 时必须携带 X-Tenant-Id 与 X-Subject-Id");
            return;
        }

        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            metrics.idempotency("too_large");
            problems.write(response, 413, "IDEMPOTENCY_BODY_TOO_LARGE",
                    "幂等请求体不能超过 2MB");
            return;
        }

        String endpoint = request.getMethod() + " " + request.getRequestURI();
        String hash = hash(body);
        IdempotencyService.Claim claim = service.claim(endpoint, key.trim(), hash);

        switch (claim.outcome()) {
            case REPLAY -> {
                metrics.idempotency("replay");
                replay(response, claim);
                return;
            }
            case IN_PROGRESS -> {
                metrics.idempotency("in_progress");
                problems.write(response, 409, "IDEMPOTENCY_IN_PROGRESS",
                        "相同幂等键的请求正在执行，请稍后重试");
                return;
            }
            case KEY_REUSED -> {
                metrics.idempotency("key_reused");
                problems.write(response, 422, "IDEMPOTENCY_KEY_REUSED",
                        "幂等键已用于不同的请求内容，请为新请求换一个键");
                return;
            }
            case ACQUIRED -> {
                // 继续往下走
            }
        }

        CachedBodyResponse cached = new CachedBodyResponse(response);
        try {
            chain.doFilter(new CachedBodyRequest(request, body), cached);
        } catch (Exception ex) {
            releaseQuietly(claim.recordId());
            metrics.idempotency("failed");
            throw ex;
        }

        int status = cached.getStatus();
        if (status >= 500) {
            releaseQuietly(claim.recordId());
            metrics.idempotency("failed");
        } else {
            byte[] captured = cached.body();
            service.complete(claim.recordId(), status, cached.getContentType(), captured);
            metrics.idempotency("stored");
        }
        cached.copyToResponse();
        log.info("幂等请求完成 endpoint={} key={} status={}", endpoint, key.trim(), status);
    }

    private void replay(HttpServletResponse response, IdempotencyService.Claim claim) throws IOException {
        response.setStatus(claim.status());
        if (claim.contentType() != null) {
            response.setContentType(claim.contentType());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        if (claim.body() != null) {
            byte[] bytes = claim.body().getBytes(StandardCharsets.UTF_8);
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        }
    }

    private void releaseQuietly(java.util.UUID recordId) {
        try {
            service.release(recordId);
        } catch (RuntimeException e) {
            log.warn("释放幂等占位失败 recordId={} error={}", recordId, e.getMessage());
        }
    }

    private static String hash(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    /** 把请求体读进内存后仍可重复读取，业务代码拿到的 InputStream 与原始请求一致。 */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // 同步读取，不需要异步回调
                }

                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return source.read(target, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** 把响应体写进内存，既用于缓存也用于事务外的统一回写。 */
    static final class CachedBodyResponse extends HttpServletResponseWrapper {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private ServletOutputStream stream;
        private PrintWriter writer;

        CachedBodyResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) {
                throw new IllegalStateException("getWriter() 已调用，不能再取 OutputStream");
            }
            if (stream == null) {
                stream = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        // 同步写出
                    }

                    @Override
                    public void write(int b) {
                        buffer.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        buffer.write(b, off, len);
                    }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (stream != null) {
                throw new IllegalStateException("getOutputStream() 已调用，不能再取 Writer");
            }
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8), true);
            }
            return writer;
        }

        @Override
        public void flushBuffer() {
            if (writer != null) {
                writer.flush();
            }
        }

        @Override
        public void resetBuffer() {
            buffer.reset();
        }

        byte[] body() {
            if (writer != null) {
                writer.flush();
            }
            return buffer.toByteArray();
        }

        void copyToResponse() throws IOException {
            if (getResponse().isCommitted()) {
                return;
            }
            byte[] bytes = body();
            if (bytes.length > 0) {
                getResponse().getOutputStream().write(bytes);
            }
        }
    }
}
