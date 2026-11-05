package com.hengce.aps.infrastructure.persistence.mrp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 在途库存 Mapper；MRP 只读统计，租户与逻辑删除条件由拦截器统一追加。
 */
public interface InventoryTransitMapper extends BaseMapper<InventoryTransit> {
}
