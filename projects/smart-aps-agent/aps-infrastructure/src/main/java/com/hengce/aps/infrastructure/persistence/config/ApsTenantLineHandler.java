package com.hengce.aps.infrastructure.persistence.config;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.hengce.aps.common.tenant.TenantContext;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

/**
 * 多租户 SQL 拦截处理器。
 *
 * <p>读取 {@link TenantContext} 作为租户值：INSERT 自动追加 tenant_id 列（实体已显式赋值时跳过），
 * SELECT/UPDATE/DELETE 自动追加 {@code tenant_id = ?} 条件。租户上下文未设置时回落到默认租户。</p>
 *
 * <p>忽略表：{@code tenant} 无 tenant_id 列；{@code role_permission} / {@code user_role} 为
 * RBAC 关联表，DDL 未设 tenant_id，需显式忽略，避免 SQL 解析后被追加不存在的列。</p>
 *
 * <p>开关 {@code aps.tenant.filter.enabled=false} 时忽略所有表，保证关闭态下没有任何表残留谓词。</p>
 */
public class ApsTenantLineHandler implements TenantLineHandler {

    /**
     * 不含 tenant_id 列、必须跳过租户注入的表。
     */
    public static final Set<String> IGNORED_TABLES = Set.of("tenant", "role_permission", "user_role");

    /**
     * 租户列名。
     */
    public static final String TENANT_ID_COLUMN = "tenant_id";

    private final ApsTenantFilterProperties properties;

    /**
     * 构造 handler。
     *
     * @param properties 租户过滤开关配置
     */
    public ApsTenantLineHandler(ApsTenantFilterProperties properties) {
        this.properties = properties;
    }

    @Override
    public Expression getTenantId() {
        return new StringValue(TenantContext.getTenantId().toString());
    }

    @Override
    public String getTenantIdColumn() {
        return TENANT_ID_COLUMN;
    }

    @Override
    public boolean ignoreTable(String tableName) {
        if (!properties.isEnabled()) {
            return true;
        }
        return tableName != null && IGNORED_TABLES.contains(tableName.toLowerCase(Locale.ROOT));
    }
}
