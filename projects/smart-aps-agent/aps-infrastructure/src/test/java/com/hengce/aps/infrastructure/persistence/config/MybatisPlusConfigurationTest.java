package com.hengce.aps.infrastructure.persistence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 持久层配置与自动填充测试（不依赖真实数据库）。
 */
class MybatisPlusConfigurationTest {

    private static final UUID OPERATOR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID OTHER_TENANT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    void shouldAssembleInterceptorsInOrder() {
        MybatisPlusInterceptor interceptor =
                new MybatisPlusConfiguration(new ApsTenantFilterProperties()).mybatisPlusInterceptor();
        List<InnerInterceptor> innerInterceptors = interceptor.getInterceptors();

        assertEquals(3, innerInterceptors.size());
        assertInstanceOf(TenantLineInnerInterceptor.class, innerInterceptors.get(0));
        PaginationInnerInterceptor pagination =
                assertInstanceOf(PaginationInnerInterceptor.class, innerInterceptors.get(1));
        assertEquals(200L, pagination.getMaxLimit());
        assertInstanceOf(OptimisticLockerInnerInterceptor.class, innerInterceptors.get(2));
    }

    @Test
    void shouldFillTenantAndAuditFieldsOnInsert() {
        ApsMetaObjectHandler handler = new ApsMetaObjectHandler();
        SampleEntity entity = new SampleEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);
        AuditContext.setCurrentUserId(OPERATOR_ID);

        handler.insertFill(metaObject);

        assertEquals(TenantContext.DEFAULT_TENANT_ID, entity.getTenantId());
        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
        assertEquals(ZoneOffset.UTC, entity.getCreatedAt().getOffset());
        assertEquals(OPERATOR_ID, entity.getCreatedBy());
        assertEquals(OPERATOR_ID, entity.getUpdatedBy());
    }

    @Test
    void shouldKeepExplicitTenantOnInsert() {
        ApsMetaObjectHandler handler = new ApsMetaObjectHandler();
        SampleEntity entity = new SampleEntity();
        entity.setTenantId(OTHER_TENANT);
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        assertEquals(OTHER_TENANT, entity.getTenantId());
    }

    @Test
    void shouldRefreshUpdatedFieldsOnUpdate() {
        ApsMetaObjectHandler handler = new ApsMetaObjectHandler();
        SampleEntity entity = new SampleEntity();
        OffsetDateTime createdAt = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime oldUpdatedAt = OffsetDateTime.of(2020, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(oldUpdatedAt);
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.updateFill(metaObject);

        assertEquals(createdAt, entity.getCreatedAt());
        assertTrue(entity.getUpdatedAt().isAfter(oldUpdatedAt));
        assertNull(entity.getUpdatedBy());
    }

    @Test
    void shouldFillUpdatedByWhenOperatorPresent() {
        ApsMetaObjectHandler handler = new ApsMetaObjectHandler();
        SampleEntity entity = new SampleEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);
        AuditContext.setCurrentUserId(OPERATOR_ID);

        handler.updateFill(metaObject);

        assertEquals(OPERATOR_ID, entity.getUpdatedBy());
    }

    private static final class SampleEntity extends BaseEntity {

        private static final long serialVersionUID = 1L;
    }
}
