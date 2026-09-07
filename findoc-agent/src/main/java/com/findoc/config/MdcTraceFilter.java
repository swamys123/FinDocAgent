package com.findoc.config;

import com.findoc.util.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

public class MdcTraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put("trace_id", traceId);
        response.setHeader("X-Trace-Id", traceId);

        UUID tenantId = TenantContext.tenantIdOrNull();
        if (tenantId != null) {
            MDC.put("tenant_id", tenantId.toString());
        }

        UUID userId = TenantContext.userIdOrNull();
        if (userId != null) {
            MDC.put("user_id", userId.toString());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("trace_id");
            MDC.remove("tenant_id");
            MDC.remove("user_id");
        }
    }
}
