package com.acme.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.platform.appcatalog.ModuleActivationService;
import jakarta.servlet.FilterChain;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 模块停用拦截：白名单、停用拒绝与灰度开关。 */
class ModuleActivationFilterTest {

    private final ModuleActivationService service = mock(ModuleActivationService.class);
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void disabledModuleIsRejectedWithStableCode() throws Exception {
        when(service.enabledModuleCodes()).thenReturn(Set.of("worklog"));
        ModuleActivationFilter filter = new ModuleActivationFilter(service, true);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/expense/documents"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("MODULE_DISABLED");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void enabledModuleAndUnmappedPathsPassThrough() throws Exception {
        when(service.enabledModuleCodes()).thenReturn(Set.of("worklog"));
        ModuleActivationFilter filter = new ModuleActivationFilter(service, true);

        MockHttpServletResponse worklog = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/worklog/days"), worklog, chain);
        assertThat(worklog.getStatus()).isEqualTo(200);

        MockHttpServletResponse jobs = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/system/jobs"), jobs, chain);
        assertThat(jobs.getStatus()).isEqualTo(200);
    }

    @Test
    void moduleCenterAndCapabilitiesAreAlwaysReachable() throws Exception {
        when(service.enabledModuleCodes()).thenReturn(Set.of());
        ModuleActivationFilter filter = new ModuleActivationFilter(service, true);

        MockHttpServletResponse appCatalog = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/app-catalog/modules"), appCatalog, chain);
        assertThat(appCatalog.getStatus()).isEqualTo(200);

        MockHttpServletResponse capabilities = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/me/capabilities"), capabilities, chain);
        assertThat(capabilities.getStatus()).isEqualTo(200);
    }

    @Test
    void disabledEnforcementSwitchPassesEverything() throws Exception {
        ModuleActivationFilter filter = new ModuleActivationFilter(service, false);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/expense/documents"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(service, never()).enabledModuleCodes();
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }
}
