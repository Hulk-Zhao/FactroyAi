package com.hengce.aps.infrastructure.persistence.resource;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 资源主数据持久层配置：Mapper 扫描入口。
 *
 * <p>BD-01 的 {@code @MapperScan} 仅覆盖 {@code persistence.mapper}，资源与资源组 Mapper 均位于
 * {@code persistence.resource}，因此追加独立扫描入口，不改动 BD-01 共享配置。</p>
 */
@Configuration
@MapperScan(basePackageClasses = ResourceMapper.class)
public class ResourcePersistenceConfiguration {
}
