package com.hengce.aps.infrastructure.persistence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 租户过滤开关配置。
 *
 * <p>配置项 {@code aps.tenant.filter.enabled} 默认 {@code true}。启动时由 Spring Boot 绑定一次，
 * 后续 SQL 执行只读取本对象的内存标志，不再读取环境变量。可通过环境变量
 * {@code APS_TENANT_FILTER_ENABLED} 或 JVM 参数 {@code -Daps.tenant.filter.enabled=false} 覆盖。</p>
 *
 * <p>关闭该开关仅用于单租户历史数据迁移 / 排障：所有表都不再注入 tenant_id 谓词，
 * 启动日志会打印 WARN 提示，避免静默降级。</p>
 */
@ConfigurationProperties(prefix = ApsTenantFilterProperties.PREFIX)
public class ApsTenantFilterProperties {

    /**
     * 配置前缀。
     */
    public static final String PREFIX = "aps.tenant.filter";

    /**
     * 默认值：开启。
     */
    public static final boolean DEFAULT_ENABLED = true;

    /**
     * 是否开启租户过滤。
     */
    private boolean enabled = DEFAULT_ENABLED;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
