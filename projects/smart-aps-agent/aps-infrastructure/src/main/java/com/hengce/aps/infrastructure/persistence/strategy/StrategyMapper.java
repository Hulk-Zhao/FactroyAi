package com.hengce.aps.infrastructure.persistence.strategy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 策略 Mapper；tenant_id 由 BD-01 的租户拦截器统一追加。
 */
public interface StrategyMapper extends BaseMapper<Strategy> {
}
