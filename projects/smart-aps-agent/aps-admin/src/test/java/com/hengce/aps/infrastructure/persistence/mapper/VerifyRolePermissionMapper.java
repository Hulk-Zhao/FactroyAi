package com.hengce.aps.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengce.aps.verify.entity.VerifyRolePermissionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 仅本地真库回归使用（test scope）：验证 role_permission 被租户拦截器忽略。
 */
@Mapper
public interface VerifyRolePermissionMapper extends BaseMapper<VerifyRolePermissionEntity> {
}
