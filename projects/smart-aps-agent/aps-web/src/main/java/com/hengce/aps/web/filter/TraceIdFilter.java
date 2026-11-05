package com.hengce.aps.web.filter;

import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.trace.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * traceId 过滤器：生成/透传 {@code X-Trace-Id}，写入 MDC 与响应头，并记录访问日志。
 *
 * <p>入站 traceId 仅接受安全字符（8-64 位），避免日志注入；缺失或非法时生成 32 位无连字符 UUID。
 * {@code tenantId} 从 {@code X-Tenant-Id} 透传，MVP 缺省使用内置 DEMO 租户。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceIdFilter.class);

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    private static final String TENANT_HEADER = ApsConstants.HEADER_TENANT_ID;

    private static final String DEFAULT_TENANT_ID = ApsConstants.DEFAULT_TENANT_ID;

    private static final String DURATION_MDC_KEY = "durationMs";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        String tenantId = resolveTenantId(request);
        long startNanos = System.nanoTime();

        TraceContext.setTraceId(traceId);
        MDC.put(TraceContext.MDC_KEY, traceId);
        MDC.put(TraceContext.TENANT_MDC_KEY, tenantId);
        response.setHeader(TraceContext.HEADER_NAME, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            MDC.put(DURATION_MDC_KEY, Long.toString(durationMs));
            LOGGER.info("http_request method={} path={} status={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            MDC.remove(DURATION_MDC_KEY);
            MDC.remove(TraceContext.TENANT_MDC_KEY);
            MDC.remove(TraceContext.MDC_KEY);
            TraceContext.clear();
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String inbound = request.getHeader(TraceContext.HEADER_NAME);
        if (inbound != null && SAFE_TRACE_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String resolveTenantId(HttpServletRequest request) {
        String tenantId = request.getHeader(TENANT_HEADER);
        if (tenantId != null && SAFE_TRACE_ID.matcher(tenantId).matches()) {
            return tenantId;
        }
        return DEFAULT_TENANT_ID;
    }
}
