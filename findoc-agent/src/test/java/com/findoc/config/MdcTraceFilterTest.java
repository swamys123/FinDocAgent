package com.findoc.config;

import com.findoc.util.TenantContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MdcTraceFilterTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        MDC.clear();
    }

    @Test
    void setsTraceAndTenantContextInMdc() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        TenantContext.set(tenantId, userId);

        MdcTraceFilter filter = new MdcTraceFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            assertThat(MDC.get("trace_id")).isNotBlank();
            assertThat(MDC.get("tenant_id")).isEqualTo(tenantId.toString());
            assertThat(MDC.get("user_id")).isEqualTo(userId.toString());
        };

        filter.doFilter(request, response, chain);

        assertThat(MDC.get("trace_id")).isNull();
        assertThat(MDC.get("tenant_id")).isNull();
        assertThat(MDC.get("user_id")).isNull();
    }
}
