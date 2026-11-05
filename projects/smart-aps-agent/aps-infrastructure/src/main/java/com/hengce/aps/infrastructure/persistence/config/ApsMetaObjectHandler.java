package com.hengce.aps.infrastructure.persistence.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.ibatis.reflection.MetaObject;

/**
 * 租户与审计字段自动填充。
 *
 * <p>填充规则：</p>
 * <ul>
 *   <li>INSERT：tenant_id、created_at、updated_at 为空时填充；created_by / updated_by 仅在
 *       {@link AuditContext} 已注入操作人时填充。</li>
 *   <li>UPDATE：强制刷新 updated_at；updated_by 仅在已注入操作人时覆盖。</li>
 *   <li>显式赋值优先：实体已带 tenant_id / created_by 时不覆盖，满足「允许显式覆盖」。</li>
 *   <li>时间统一 UTC {@link OffsetDateTime}，对应 TIMESTAMPTZ。</li>
 * </ul>
 * <p>说明：字段 fill 标记位于 {@code BaseEntity}，本处理器仅对带对应 fill 标注的实体生效。</p>
 */
public class ApsMetaObjectHandler implements MetaObjectHandler {

    static final String FIELD_TENANT_ID = "tenantId";

    static final String FIELD_CREATED_AT = "createdAt";

    static final String FIELD_UPDATED_AT = "updatedAt";

    static final String FIELD_CREATED_BY = "createdBy";

    static final String FIELD_UPDATED_BY = "updatedBy";

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID currentUserId = AuditContext.getCurrentUserId();
        fillIfNull(metaObject, FIELD_TENANT_ID, TenantContext.getTenantId());
        fillIfNull(metaObject, FIELD_CREATED_AT, now);
        fillIfNull(metaObject, FIELD_UPDATED_AT, now);
        fillIfNull(metaObject, FIELD_CREATED_BY, currentUserId);
        fillIfNull(metaObject, FIELD_UPDATED_BY, currentUserId);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName(FIELD_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC), metaObject);
        UUID currentUserId = AuditContext.getCurrentUserId();
        if (currentUserId != null) {
            setFieldValByName(FIELD_UPDATED_BY, currentUserId, metaObject);
        }
    }

    private void fillIfNull(MetaObject metaObject, String fieldName, Object value) {
        if (value == null || !metaObject.hasSetter(fieldName)) {
            return;
        }
        if (metaObject.getValue(fieldName) == null) {
            metaObject.setValue(fieldName, value);
        }
    }
}
