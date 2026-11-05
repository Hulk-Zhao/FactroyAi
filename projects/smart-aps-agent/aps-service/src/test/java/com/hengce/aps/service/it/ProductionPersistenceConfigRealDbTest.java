package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hengce.aps.infrastructure.persistence.process.template.handler.UuidTypeHandler;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 确认真库测试跑在生产持久层配置上：UUID/Serializable 全局 TypeHandler 由
 * {@code MybatisTypeHandlerConfiguration} 注册，CalendarMapper.xml 能解析 UUID 参数。
 *
 * <p>历史回归 harness 曾在测试侧自行注册 UUID TypeHandler；生产侧补齐后，
 * 本测试以生产装配的 SqlSessionFactory 为准，防止再次回退。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class ProductionPersistenceConfigRealDbTest extends AbstractRealDbIntegrationTest {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    void productionTypeHandlerRegistryRegistersUuidAndSerializable() {
        TypeHandlerRegistry registry = sqlSessionFactory.getConfiguration().getTypeHandlerRegistry();
        TypeHandler<?> uuidHandler = registry.getTypeHandler(UUID.class);
        TypeHandler<?> serializableHandler = registry.getTypeHandler(Serializable.class);

        assertNotNull(uuidHandler);
        assertEquals(UuidTypeHandler.class, uuidHandler.getClass(),
                "UUID TypeHandler 必须来自生产配置 MybatisTypeHandlerConfiguration");
        assertNotNull(serializableHandler);
        assertEquals(ObjectTypeHandler.class, serializableHandler.getClass(),
                "Serializable（BaseEntity.id）必须使用 ObjectTypeHandler");
        evidence("SqlSessionFactory TypeHandlerRegistry: UUID -> " + uuidHandler.getClass().getName()
                + ", Serializable -> " + serializableHandler.getClass().getName());
    }

    @Test
    void calendarMapperXmlResolvesUuidParameters() {
        String name = tag("CAL-UUID");
        var calendar = createCalendar(name, 1, LocalDate.now(ZoneOffset.UTC), null, "FACTORY", null,
                List.of(shift("08:00", "17:00")));

        assertNotNull(calendarService.get(calendar.id()), "UUID 入参经 XML 查询必须返回实体");
        evidence("CalendarMapper.findById(UUID=" + calendar.id() + ") -> " + name + " 查询成功");
    }
}
