package com.hengce.aps.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.rbac.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.method.HandlerMethod;

/**
 * C30 权限注解拦截：401（未认证）/ 403（无权限）/ off 放行 / API Key 等价 ADMIN。
 */
@ExtendWith(MockitoExtension.class)
class PermissionInterceptorTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000303");

    @Mock
    private PermissionService permissionService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private PermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new PermissionInterceptor(permissionService);
        ApsSecurityContext.clear();
    }

    @AfterEach
    void tearDown() {
        ApsSecurityContext.clear();
    }

    @Test
    @DisplayName("off 模式：任何注解都放行")
    void offModeAllowsAll() throws NoSuchMethodException {
        ApsSecurityContext.setMode("off");

        assertTrue(interceptor.preHandle(request, response, handler("list")));
        verify(permissionService, never()).hasPermission(eq(USER_ID), anyString());
    }

    @Test
    @DisplayName("已认证但缺权限：40300")
    void missingPermissionReturns403() throws NoSuchMethodException {
        ApsSecurityContext.setMode("prod");
        ApsSecurityContext.setPrincipal(new AuthPrincipal(USER_ID, List.of("SYS_USER"), false,
                AuthMechanism.JWT));
        when(permissionService.hasPermission(USER_ID, "SYS_USER")).thenReturn(false);

        BizException exception = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, handler("list")));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    @Test
    @DisplayName("已认证且拥有权限：放行")
    void grantedPermissionAllows() throws NoSuchMethodException {
        ApsSecurityContext.setMode("prod");
        ApsSecurityContext.setPrincipal(new AuthPrincipal(USER_ID, List.of("SYS_USER"), false,
                AuthMechanism.JWT));
        when(permissionService.hasPermission(USER_ID, "SYS_USER")).thenReturn(true);

        assertTrue(interceptor.preHandle(request, response, handler("list")));
    }

    @Test
    @DisplayName("dev API Key 等价 ADMIN：跳过硬权限检查")
    void apiKeyAdminEquivalentAllows() throws NoSuchMethodException {
        ApsSecurityContext.setMode("dev");
        ApsSecurityContext.setPrincipal(new AuthPrincipal(null, List.of(), true, AuthMechanism.API_KEY));

        assertTrue(interceptor.preHandle(request, response, handler("list")));
        verify(permissionService, never()).hasPermission(eq(USER_ID), anyString());
    }

    @Test
    @DisplayName("未认证访问带注解端点：40100")
    void unauthenticatedReturns401() throws NoSuchMethodException {
        ApsSecurityContext.setMode("prod");

        BizException exception = assertThrows(BizException.class,
                () -> interceptor.preHandle(request, response, handler("list")));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    @Test
    @DisplayName("仅认证注解：有主体即放行，不查权限")
    void authenticatedOnlyAllowsWithoutPermission() throws NoSuchMethodException {
        ApsSecurityContext.setMode("prod");
        ApsSecurityContext.setPrincipal(new AuthPrincipal(USER_ID, List.of("VIEWER"), false,
                AuthMechanism.JWT));

        assertTrue(interceptor.preHandle(request, response, handler("authOnly")));
        verify(permissionService, never()).hasPermission(eq(USER_ID), anyString());
    }

    @Test
    @DisplayName("无注解端点不做权限判断")
    void noAnnotationAllows() throws NoSuchMethodException {
        ApsSecurityContext.setMode("prod");

        assertTrue(interceptor.preHandle(request, response, handler("open")));
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        DummyController bean = new DummyController();
        Method method = DummyController.class.getMethod(methodName);
        return new HandlerMethod(bean, method);
    }

    /**
     * 测试用控制器。
     */
    static class DummyController {

        @RequiresPermission("SYS_USER")
        public void list() {
        }

        @RequiresPermission
        public void authOnly() {
        }

        public void open() {
        }
    }
}
