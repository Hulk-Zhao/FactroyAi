package com.hengce.aps.infrastructure.persistence.audit;

import java.util.UUID;

/**
 * 线程级操作人上下文，供审计字段 created_by / updated_by 自动填充。
 *
 * <p>M1 尚未接入认证，过滤器/网关在完成鉴权后调用 {@link #setCurrentUserId(UUID)} 注入；
 * 未注入时保持 null（DDL 允许 created_by / updated_by 为空），不伪造系统账号。</p>
 *
 * <p><strong>身份来源</strong>：除用户 id 外还记录 {@link IdentitySource}。仅当 dev 模式下
 * 显式开启 {@code X-User-Id} 时来源为 {@link IdentitySource#DEV_HEADER}，审计落库会在
 * {@code audit_log.user_agent} 写入 {@code identity-source=DEV_HEADER(X-User-Id)} 标记，
 * 避免与真实 JWT 用户混淆。</p>
 */
public final class AuditContext {

    /**
     * 审计身份来源标记，写入 {@code audit_log.user_agent}。
     */
    public static final String DEV_HEADER_MARKER = "identity-source=DEV_HEADER(X-User-Id)";

    private static final ThreadLocal<UUID> CURRENT_USER_ID = new ThreadLocal<>();

    private static final ThreadLocal<IdentitySource> IDENTITY_SOURCE = new ThreadLocal<>();

    private AuditContext() {
    }

    /**
     * 读取当前操作人。
     *
     * @return 当前用户 id，未设置时返回 null
     */
    public static UUID getCurrentUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 设置当前操作人。
     *
     * @param userId 用户 id；null 表示清除
     */
    public static void setCurrentUserId(UUID userId) {
        if (userId == null) {
            CURRENT_USER_ID.remove();
        } else {
            CURRENT_USER_ID.set(userId);
        }
    }

    /**
     * 读取身份来源。
     *
     * @return 来源，未设置时为 null
     */
    public static IdentitySource getIdentitySource() {
        return IDENTITY_SOURCE.get();
    }

    /**
     * 设置身份来源。
     *
     * @param source 来源；null 表示清除
     */
    public static void setIdentitySource(IdentitySource source) {
        if (source == null) {
            IDENTITY_SOURCE.remove();
        } else {
            IDENTITY_SOURCE.set(source);
        }
    }

    /**
     * 清理当前线程操作人，必须在线程归还池前调用。
     */
    public static void clear() {
        CURRENT_USER_ID.remove();
        IDENTITY_SOURCE.remove();
    }

    /**
     * 审计身份来源。
     */
    public enum IdentitySource {

        /** JWT subject（prod 与 dev Bearer）。 */
        JWT,

        /** dev 模式下由 {@code X-User-Id} 请求头指定。 */
        DEV_HEADER,

        /** 由配置 {@code aps.audit.dev-user-id} 指定。 */
        CONFIGURED
    }
}
