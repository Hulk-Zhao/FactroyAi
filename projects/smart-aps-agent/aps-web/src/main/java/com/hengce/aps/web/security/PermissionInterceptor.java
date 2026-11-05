package com.hengce.aps.web.security;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.rbac.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 权限拦截器（C30）：认证后检查 {@link RequiresPermission} 声明的权限码。
 *
 * <p>off 模式全部放行；其余模式未认证 → 40100，已认证但缺权限 → 40300。
 * dev 模式 API Key 等价 ADMIN（{@link AuthPrincipal#adminEquivalent()}）。</p>
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final PermissionService permissionService;

    /**
     * 构造拦截器。
     *
     * @param permissionService 权限服务
     */
    public PermissionInterceptor(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        if (ApsSecurityContext.isOff()) {
            return true;
        }
        RequiresPermission annotation = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequiresPermission.class);
        }
        if (annotation == null) {
            return true;
        }
        AuthPrincipal principal = ApsSecurityContext.getPrincipal();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String required = annotation.value();
        if (required.isBlank() || principal.adminEquivalent()) {
            return true;
        }
        if (!permissionService.hasPermission(principal.userId(), required)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return true;
    }
}
