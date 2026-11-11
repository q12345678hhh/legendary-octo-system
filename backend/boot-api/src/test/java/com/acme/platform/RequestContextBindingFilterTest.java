package com.acme.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestContextBindingFilterTest {

    private final RequestContextBindingFilter filter = new RequestContextBindingFilter();

    @AfterEach
    void clearContext() {
        RequestContextHolder.clear();
    }

    @Test
    void bindsIncomingHeadersAndEchoesThem() throws Exception {
        UUID tenantId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestContextBindingFilter.CORRELATION_HEADER, "corr-smoke");
        request.addHeader(RequestContextBindingFilter.TRACE_HEADER, "0123456789abcdef0123456789abcdef");
        request.addHeader(RequestContextBindingFilter.TENANT_HEADER, tenantId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<RequestContext> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(RequestContextHolder.find().orElseThrow()));

        assertThat(seen.get())
                .isEqualTo(new RequestContext(tenantId, "corr-smoke", "0123456789abcdef0123456789abcdef"));
        assertThat(response.getHeader(RequestContextBindingFilter.CORRELATION_HEADER)).isEqualTo("corr-smoke");
        assertThat(response.getHeader(RequestContextBindingFilter.TRACE_HEADER))
                .isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(RequestContextHolder.find()).isEmpty();
    }

    @Test
    void generatesCorrelationAndTraceIdsWhenMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestContextBindingFilter.CORRELATION_HEADER)).isNotBlank();
        assertThat(response.getHeader(RequestContextBindingFilter.TRACE_HEADER)).matches("[0-9a-f]{32}");
    }

    @Test
    void ignoresClientSuppliedSubjectHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Id", "forged-operator");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<RequestContext> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(RequestContextHolder.find().orElseThrow()));

        assertThat(seen.get().subjectId()).isNull();
    }

    @Test
    void ignoresInvalidTenantAndTraceHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestContextBindingFilter.TENANT_HEADER, "not-a-uuid");
        request.addHeader(RequestContextBindingFilter.TRACE_HEADER, "trace-not-hex");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<RequestContext> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(RequestContextHolder.find().orElseThrow()));

        assertThat(seen.get().tenantId()).isNull();
        assertThat(seen.get().traceId()).matches("[0-9a-f]{32}");
    }
}
