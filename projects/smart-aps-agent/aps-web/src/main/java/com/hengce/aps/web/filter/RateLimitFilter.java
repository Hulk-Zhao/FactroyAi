package com.hengce.aps.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.web.config.ApiSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M2 限流过滤器（C27）：单实例固定窗口计数，默认 100 req/min。
 *
 * <p>计数维度为 {@code tenantId + 调用方指纹}（API Key 哈希或 Bearer 哈希，缺失时用远端地址）。
 * 租户取自认证阶段解析出的 {@link TenantContext}，<b>不信任客户端自报的 {@code X-Tenant-Id}</b>，
 * 否则换一个请求头即可获得新的限流额度。认证失败不消耗额度（本过滤器排在认证过滤器之后）。
 * 多实例部署需替换为 Redis 实现，接口语义不变。
 * 与认证默认一致：未显式配置 {@code aps.security.mode} 时按 {@code prod} 处理，限流生效。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = ApsConstants.API_PREFIX;

    private static final String HEALTH_PATH = API_PREFIX + "/health";

    private static final String API_KEY_HEADER = "X-API-Key";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final int CLEAN_THRESHOLD = 10_000;

    private final ApiSecurityProperties properties;

    private final ObjectMapper objectMapper;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(ApiSecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(API_PREFIX) || path.equals(HEALTH_PATH) || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ApiSecurityProperties.RateLimit config = properties.getRateLimit();
        if (!config.isEnabled() || "off".equals(resolveMode())) {
            filterChain.doFilter(request, response);
            return;
        }
        int capacity = Math.max(1, config.getCapacity());
        int windowSeconds = Math.max(1, config.getWindowSeconds());
        long windowStart = System.currentTimeMillis() / 1000L / windowSeconds * windowSeconds;
        long resetAt = windowStart + windowSeconds;

        Window window = windows.computeIfAbsent(resolveKey(request), key -> new Window(windowStart));
        int current = window.increment(windowStart);
        response.setHeader("X-RateLimit-Limit", Integer.toString(capacity));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(Math.max(0, capacity - current)));
        response.setHeader("X-RateLimit-Reset", Long.toString(resetAt));

        if (current > capacity) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(
                    Result.failure(ErrorCode.TOO_MANY_REQUESTS)));
            return;
        }
        if (windows.size() > CLEAN_THRESHOLD) {
            windows.entrySet().removeIf(entry -> entry.getValue().getWindowStart() < windowStart - windowSeconds);
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 解析生效的安全模式，与 {@link ApiSecurityFilter} 保持一致。
     *
     * @return {@code prod} / {@code dev} / {@code off}
     */
    private String resolveMode() {
        return properties.resolvedMode();
    }

    private String resolveKey(HttpServletRequest request) {
        // 服务端可信来源：认证过滤器已把 JWT/dev 身份写入 TenantContext；
        // 未认证路径（无租户上下文）回落到默认租户，绝不读取客户端自报的 X-Tenant-Id。
        UUID tenantId = TenantContext.getTenantId();
        String tenant = tenantId == null ? ApsConstants.DEFAULT_TENANT_ID : tenantId.toString();
        String apiKey = request.getHeader(API_KEY_HEADER);
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        String caller;
        if (apiKey != null && !apiKey.isBlank()) {
            caller = "key:" + Integer.toHexString(apiKey.hashCode());
        } else if (authorization != null && !authorization.isBlank()) {
            caller = "bearer:" + Integer.toHexString(authorization.hashCode());
        } else {
            caller = "ip:" + request.getRemoteAddr();
        }
        return tenant + "|" + caller;
    }

    /**
     * 固定窗口计数。
     */
    private static final class Window {

        private volatile long windowStart;

        private final AtomicInteger count = new AtomicInteger();

        Window(long windowStart) {
            this.windowStart = windowStart;
        }

        long getWindowStart() {
            return windowStart;
        }

        int increment(long currentWindowStart) {
            if (windowStart != currentWindowStart) {
                synchronized (this) {
                    if (windowStart != currentWindowStart) {
                        windowStart = currentWindowStart;
                        count.set(0);
                    }
                }
            }
            return count.incrementAndGet();
        }
    }
}
