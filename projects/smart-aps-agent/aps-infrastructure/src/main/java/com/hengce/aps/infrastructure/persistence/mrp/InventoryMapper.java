package com.hengce.aps.infrastructure.persistence.mrp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 库存 Mapper；租户条件由 {@code TenantLineInnerInterceptor} 自动追加，逻辑删除由实体注解处理。
 */
public interface InventoryMapper extends BaseMapper<Inventory> {
}
