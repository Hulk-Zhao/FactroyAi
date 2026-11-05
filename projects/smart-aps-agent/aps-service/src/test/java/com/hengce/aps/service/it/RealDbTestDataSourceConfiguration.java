package com.hengce.aps.service.it;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 真库数据源，参数仅来自 {@link RealDbEnvironment}（环境变量）。
 *
 * <p>显式提供 DataSource 后，Spring Boot 的 DataSourceAutoConfiguration 让位，
 * 不会因缺少 application.yml 而回落到嵌入式数据库。测试串行执行，无需连接池。</p>
 */
@Configuration(proxyBeanMethods = false)
public class RealDbTestDataSourceConfiguration {

    /**
     * 构建直连数据源。
     *
     * @return PostgreSQL 数据源
     */
    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(RealDbEnvironment.jdbcUrl());
        dataSource.setUsername(RealDbEnvironment.username());
        dataSource.setPassword(RealDbEnvironment.password());
        return dataSource;
    }
}
