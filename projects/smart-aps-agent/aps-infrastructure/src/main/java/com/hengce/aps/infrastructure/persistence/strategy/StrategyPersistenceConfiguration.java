package com.hengce.aps.infrastructure.persistence.strategy;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 策略与约束 DSL 持久层配置：Mapper 扫描入口。
 *
 * <p>BD-01 的 {@code @MapperScan} 仅覆盖 {@code persistence.mapper}，本包追加独立扫描入口，
 * 不改动共享配置。</p>
 */
@Configuration
@MapperScan(basePackages = "com.hengce.aps.infrastructure.persistence.strategy")
public class StrategyPersistenceConfiguration {
}
