package com.hengce.aps.infrastructure.persistence.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import com.hengce.aps.domain.common.BaseEntity;
import org.junit.jupiter.api.Test;

/**
 * BaseEntity 默认值与持久层注解约定测试（不依赖真实数据库）。
 */
class BaseEntityTest {

    @Test
    void shouldProvideSchemaDefaults() {
        SampleEntity entity = new SampleEntity();

        assertNull(entity.getId());
        assertNull(entity.getTenantId());
        assertEquals(BaseEntity.DEFAULT_VERSION, entity.getVersion());
        assertEquals(BaseEntity.DEFAULT_DELETED, entity.getDeleted());
        assertNull(entity.getCreatedAt());
        assertNull(entity.getUpdatedAt());
        assertNull(entity.getCreatedBy());
        assertNull(entity.getUpdatedBy());
    }

    @Test
    void shouldExposeVersionAndLogicDeleteMarkers() throws NoSuchFieldException {
        assertTrue(BaseEntity.class.getDeclaredField("version").isAnnotationPresent(Version.class));

        TableLogic logic = BaseEntity.class.getDeclaredField("deleted").getAnnotation(TableLogic.class);
        assertEquals("false", logic.value());
        assertEquals("true", logic.delval());

        assertFill(BaseEntity.class.getDeclaredField("tenantId").getAnnotation(TableField.class),
                FieldFill.INSERT);
        assertFill(BaseEntity.class.getDeclaredField("createdAt").getAnnotation(TableField.class),
                FieldFill.INSERT);
        assertFill(BaseEntity.class.getDeclaredField("updatedAt").getAnnotation(TableField.class),
                FieldFill.INSERT_UPDATE);
    }

    @Test
    void shouldAllowExplicitValues() {
        SampleEntity entity = new SampleEntity();
        entity.setVersion(3);
        entity.setDeleted(true);

        assertEquals(3, entity.getVersion());
        assertTrue(entity.getDeleted());
        assertFalse(new SampleEntity().getDeleted());
    }

    private void assertFill(TableField tableField, FieldFill expected) {
        assertEquals(expected, tableField.fill());
    }

    private static final class SampleEntity extends BaseEntity {

        private static final long serialVersionUID = 1L;
    }
}
