package com.hengce.aps.infrastructure.persistence.bom;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * BOM 持久层配置：Mapper 扫描入口。
 *
 * <p>BD-01 的 {@code @MapperScan} 仅覆盖 {@code persistence.mapper}，BOM Mapper 位于
 * {@code persistence.bom}，因此追加独立扫描入口，不改动共享配置。</p>
 */
@Configuration
@MapperScan(basePackageClasses = {BomMapper.class, BomItemMapper.class})
public class BomPersistenceConfiguration {
}
