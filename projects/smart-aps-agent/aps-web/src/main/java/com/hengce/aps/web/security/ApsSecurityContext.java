package com.hengce.aps.web.security;

/**
 * 请求线程级认证/模式上下文，由 {@code ApiSecurityFilter} 写入、权限拦截器读取。
 */
public final class ApsSecurityContext {

    private static final ThreadLocal<String> MODE = new ThreadLocal<>();

    private static final ThreadLocal<AuthPrincipal> PRINCIPAL = new ThreadLocal<>();

    private ApsSecurityContext() {
    }

    /**
     * 设置认证模式。
     *
     * @param mode prod/dev/off
     */
    public static void setMode(String mode) {
        MODE.set(mode);
    }

    /**
     * 当前认证模式。
     *
     * @return 模式，未设置时为 null
     */
    public static String getMode() {
        return MODE.get();
    }

    /**
     * 是否为 off 模式（权限注解全部放行）。
     *
     * @return true 表示 off
     */
    public static boolean isOff() {
        return "off".equals(MODE.get());
    }

    /**
     * 设置认证主体。
     *
     * @param principal 主体；null 表示匿名
     */
    public static void setPrincipal(AuthPrincipal principal) {
        if (principal == null) {
            PRINCIPAL.remove();
        } else {
            PRINCIPAL.set(principal);
        }
    }

    /**
     * 当前认证主体。
     *
     * @return 主体，未认证时为 null
     */
    public static AuthPrincipal getPrincipal() {
        return PRINCIPAL.get();
    }

    /**
     * 清理全部线程变量；过滤器 finally 必须调用。
     */
    public static void clear() {
        MODE.remove();
        PRINCIPAL.remove();
    }
}
