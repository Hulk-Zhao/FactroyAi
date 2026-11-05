package com.hengce.aps.infrastructure.persistence.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 订单持久层配置：Mapper 扫描入口。
 *
 * <p>BD-01 的 {@code @MapperScan} 仅覆盖 {@code persistence.mapper}，订单 Mapper 位于
 * {@code persistence.order}，因此追加一个独立扫描入口，不改动共享配置。</p>
 */
@Configuration
@MapperScan(basePackages = "com.hengce.aps.infrastructure.persistence.order")
public class OrderPersistenceConfiguration {
}
