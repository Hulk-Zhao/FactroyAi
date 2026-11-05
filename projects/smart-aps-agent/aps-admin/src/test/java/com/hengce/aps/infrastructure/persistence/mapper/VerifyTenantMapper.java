package com.hengce.aps.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengce.aps.verify.entity.VerifyTenantEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 仅本地真库回归使用（test scope）：验证无 tenant_id 列的 tenant 表被租户拦截器忽略。
 */
@Mapper
public interface VerifyTenantMapper extends BaseMapper<VerifyTenantEntity> {
}
