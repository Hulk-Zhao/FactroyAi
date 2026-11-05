package com.hengce.aps.infrastructure.persistence.material;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 物料主数据持久层配置：Mapper 扫描入口。
 *
 * <p>BD-01 的 {@code @MapperScan} 仅覆盖 {@code persistence.mapper}，物料 Mapper 位于
 * {@code persistence.material}，因此追加一个独立扫描入口，不改动 BD-01 共享配置。</p>
 *
 * <p>UUID 与 {@code BaseEntity.id}（Serializable）的类型处理器由
 * {@code MybatisTypeHandlerConfiguration} 全局注册，本类不再重复注册，避免定制器
 * 执行顺序不确定导致 uuid 列退回 {@code ObjectTypeHandler}。</p>
 */
@Configuration
@MapperScan(basePackages = "com.hengce.aps.infrastructure.persistence.material")
public class MaterialPersistenceConfiguration {
}
