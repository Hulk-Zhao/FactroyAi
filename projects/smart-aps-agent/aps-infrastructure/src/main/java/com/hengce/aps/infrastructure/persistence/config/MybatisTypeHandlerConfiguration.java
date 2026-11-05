package com.hengce.aps.infrastructure.persistence.config;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.hengce.aps.infrastructure.persistence.process.template.handler.UuidTypeHandler;
import java.io.Serializable;
import java.util.UUID;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 全局类型处理器注册。
 *
 * <p>MyBatis 3.5.16 核心未内置 {@link UUID} TypeHandler，而 {@code CalendarMapper.xml}、
 * 工序模版 XML 等以 UUID 作为参数或结果类型；缺少注册会在 {@code SqlSessionFactory}
 * 解析 XML 阶段直接报 {@code Type handler was null ... javaType (java.util.UUID)}。</p>
 *
 * <p>这里通过 MyBatis-Plus 的 {@link ConfigurationCustomizer} 在装配阶段一次性注册，
 * 覆盖全部 Mapper，无需逐个声明 {@code typeHandler}：</p>
 * <ul>
 *   <li>{@link UUID} → {@link UuidTypeHandler}，按 {@code Types.OTHER} 读写 PostgreSQL uuid 列；</li>
 *   <li>{@link Serializable} → {@link ObjectTypeHandler}，用于 {@code BaseEntity.id}
 *       （同时承载 UUID 与 BIGSERIAL 主键，声明类型为 Serializable）。该注册只做按运行时
 *       值透传，不参与 JSONB 列的序列化；JSONB 字段仍由 {@code JsonbTypeHandler} 显式
 *       绑定，二者互不覆盖。</li>
 * </ul>
 */
@Configuration
public class MybatisTypeHandlerConfiguration {

    /**
     * 注册 UUID 与 BaseEntity 主键（Serializable）类型处理器。
     *
     * @return MyBatis 配置定制器
     */
    @Bean
    public ConfigurationCustomizer apsUuidTypeHandlerCustomizer() {
        return configuration -> {
            configuration.getTypeHandlerRegistry().register(UUID.class, new UuidTypeHandler());
            configuration.getTypeHandlerRegistry().register(Serializable.class, ObjectTypeHandler.class);
        };
    }
}
