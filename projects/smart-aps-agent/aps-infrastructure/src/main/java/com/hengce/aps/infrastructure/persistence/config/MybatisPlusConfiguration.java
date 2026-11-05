package com.hengce.aps.infrastructure.persistence.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.hengce.aps.common.constant.ApsConstants;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 持久层基座配置。
 *
 * <p>拦截器顺序遵循官方约定：多租户 → 分页 → 乐观锁。顺序错误会导致 1) 分页 SQL 未带租户条件、
 * 2) 乐观锁 version 条件被后续解析覆盖。</p>
 *
 * <p>Mapper 扫描根包固定为 {@code com.hengce.aps.infrastructure.persistence.mapper}，
 * 各业务流的 Mapper 必须放在该包及其子包下，避免扫描到 web/service 层。</p>
 */
@Configuration
@MapperScan(basePackages = MybatisPlusConfiguration.MAPPER_PACKAGE)
@EnableConfigurationProperties(ApsTenantFilterProperties.class)
public class MybatisPlusConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(MybatisPlusConfiguration.class);

    /**
     * Mapper 接口扫描根包。
     */
    public static final String MAPPER_PACKAGE = "com.hengce.aps.infrastructure.persistence.mapper";

    private final ApsTenantFilterProperties tenantFilterProperties;

    /**
     * 构造配置。
     *
     * @param tenantFilterProperties 租户过滤开关配置
     */
    public MybatisPlusConfiguration(ApsTenantFilterProperties tenantFilterProperties) {
        this.tenantFilterProperties = tenantFilterProperties;
    }

    /**
     * 构建 MyBatis-Plus 拦截器链。
     *
     * @return 含租户、分页、乐观锁能力的拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        if (!tenantFilterProperties.isEnabled()) {
            LOGGER.warn("租户过滤已关闭（aps.tenant.filter.enabled=false）：MyBatis 自动 tenant_id 谓词已全部停用；"
                    + "JdbcTemplate 显式谓词不受此开关控制，仅限单租户历史数据迁移/排障，请勿在正常业务环境使用");
        } else {
            LOGGER.info("租户过滤已启用（aps.tenant.filter.enabled=true）：所有非忽略表自动注入 tenant_id 谓词");
        }
        interceptor.addInnerInterceptor(
                new TenantLineInnerInterceptor(new ApsTenantLineHandler(tenantFilterProperties)));
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit((long) ApsConstants.MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    /**
     * 注册租户与审计字段自动填充处理器。
     *
     * @return MetaObjectHandler
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new ApsMetaObjectHandler();
    }
}
