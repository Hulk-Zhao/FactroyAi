package com.hengce.aps.infrastructure.persistence.mapper.setup;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengce.aps.infrastructure.persistence.setup.SetupMatrixEntryEntity;

/**
 * 换型矩阵 Mapper；租户过滤由租户拦截器统一追加，逻辑删除由 {@code @TableLogic} 处理。
 */
public interface SetupMatrixEntryMapper extends BaseMapper<SetupMatrixEntryEntity> {
}
