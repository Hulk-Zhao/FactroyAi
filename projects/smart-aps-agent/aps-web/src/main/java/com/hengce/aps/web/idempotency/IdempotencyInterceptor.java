package com.hengce.aps.web.idempotency;

import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.idempotency.IdempotencyStore;
import com.hengce.aps.common.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 幂等键拦截器。
 *
 * <p>仅拦截写请求（POST/PUT/PATCH/DELETE）。未携带 {@code Idempotency-Key} 时放行，保持兼容；
 * 携带时首次请求占用成功，重复请求返回 409；请求失败时释放，允许客户端重试。</p>
 */
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private static final String KEY_ATTRIBUTE = "aps.idempotency.scopedKey";

    private final IdempotencyStore idempotencyStore;

    /**
     * 构造拦截器。
     *
     * @param idempotencyStore 幂等键存储
     */
    public IdempotencyInterceptor(IdempotencyStore idempotencyStore) {
        this.idempotencyStore = idempotencyStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod) || !MUTATING_METHODS.contains(request.getMethod())) {
            return true;
        }
        String idempotencyKey = request.getHeader(ApsConstants.HEADER_IDEMPOTENCY_KEY);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }
        if (!KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "Idempotency-Key 需为 8-128 位字母、数字或 . _ : -");
        }
        String scopedKey = buildScopedKey(request, idempotencyKey);
        if (!idempotencyStore.acquire(scopedKey, ApsConstants.IDEMPOTENCY_TTL)) {
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        request.setAttribute(KEY_ATTRIBUTE, scopedKey);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception exception) {
        boolean failed = exception != null || response.getStatus() >= HttpServletResponse.SC_BAD_REQUEST;
        if (!failed) {
            return;
        }
        Object scopedKey = request.getAttribute(KEY_ATTRIBUTE);
        if (scopedKey instanceof String key) {
            idempotencyStore.release(key);
        }
    }

    private String buildScopedKey(HttpServletRequest request, String idempotencyKey) {
        String tenantId = MDC.get(TraceContext.TENANT_MDC_KEY);
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = ApsConstants.DEFAULT_TENANT_ID;
        }
        return tenantId + ":" + request.getMethod() + ":" + request.getRequestURI() + ":" + idempotencyKey;
    }
}
