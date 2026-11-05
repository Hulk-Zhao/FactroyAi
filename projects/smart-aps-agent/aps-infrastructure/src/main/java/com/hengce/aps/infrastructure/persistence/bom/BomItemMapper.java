package com.hengce.aps.infrastructure.persistence.bom;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * BOM 子件行 Mapper；明细查询全部走 MyBatis-Plus 条件构造器，租户与逻辑删除条件由拦截器追加。
 */
public interface BomItemMapper extends BaseMapper<BomItem> {
}
