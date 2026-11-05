package com.hengce.aps.common.tenant;

import com.hengce.aps.common.constant.ApsConstants;
import java.util.UUID;

/**
 * 线程级租户上下文。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>未显式设置时读取 {@link #DEFAULT_TENANT_ID}，保证单租户 MVP 下无需登录态也能落库。</li>
 *   <li>{@link #setTenantId(UUID)} 用于显式覆盖（网关/过滤器从认证上下文注入）；传 null 恢复默认。</li>
 *   <li>Web 过滤器与异步线程必须在使用完或归还线程池前调用 {@link #clear()}，避免租户串线。</li>
 * </ul>
 * <p>风格对齐 {@link com.hengce.aps.common.trace.TraceContext}：静态工具类、不可实例化。</p>
 */
public final class TenantContext {

    /**
     * MVP 默认租户，与 V1 种子租户及 contract-freeze C5 保持一致。
     */
    public static final UUID DEFAULT_TENANT_ID = UUID.fromString(ApsConstants.DEFAULT_TENANT_ID);

    private static final ThreadLocal<UUID> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 读取当前线程租户；未设置时返回默认租户。
     *
     * @return 当前租户标识，永不为 null
     */
    public static UUID getTenantId() {
        UUID tenantId = TENANT_ID.get();
        return tenantId == null ? DEFAULT_TENANT_ID : tenantId;
    }

    /**
     * 显式设置当前线程租户。
     *
     * @param tenantId 租户标识；null 表示清除覆盖并回落到默认租户
     */
    public static void setTenantId(UUID tenantId) {
        if (tenantId == null) {
            TENANT_ID.remove();
        } else {
            TENANT_ID.set(tenantId);
        }
    }

    /**
     * 以字符串形式设置当前线程租户，便于从请求头/令牌直接注入。
     *
     * @param tenantId UUID 字符串；空白表示清除覆盖
     * @throws IllegalArgumentException 字符串不是合法 UUID
     */
    public static void setTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            TENANT_ID.remove();
            return;
        }
        TENANT_ID.set(UUID.fromString(tenantId.trim()));
    }

    /**
     * 判断当前线程是否显式设置过租户（而非使用默认值）。
     *
     * @return true 表示已显式覆盖
     */
    public static boolean hasExplicitTenant() {
        return TENANT_ID.get() != null;
    }

    /**
     * 清理当前线程租户，必须在线程归还池前调用。
     */
    public static void clear() {
        TENANT_ID.remove();
    }
}
