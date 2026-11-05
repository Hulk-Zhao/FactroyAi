package com.hengce.aps.infrastructure.persistence.mrp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 供应商只读 Mapper；租户与逻辑删除条件由拦截器统一追加。
 */
public interface SupplierRefMapper extends BaseMapper<SupplierRef> {
}
