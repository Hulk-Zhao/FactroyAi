package com.hengce.aps.agent.it;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 真库数据源：参数仅来自 {@link AgentDbEnvironment}（环境变量），测试串行执行无需连接池。
 */
@Configuration(proxyBeanMethods = false)
public class AgentDbDataSourceConfiguration {

    /**
     * 构建直连数据源。
     *
     * @return PostgreSQL 数据源
     */
    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(AgentDbEnvironment.jdbcUrl());
        dataSource.setUsername(AgentDbEnvironment.username());
        dataSource.setPassword(AgentDbEnvironment.password());
        return dataSource;
    }
}
