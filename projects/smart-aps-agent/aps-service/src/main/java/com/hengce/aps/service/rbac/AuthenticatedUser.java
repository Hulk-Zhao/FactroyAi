package com.hengce.aps.service.rbac;

import java.util.List;
import java.util.UUID;

/**
 * 登录成功的认证主体（C30），供 Web 层签发 JWT。
 *
 * @param userId 用户主键
 * @param tenantId 用户实际所属租户（来自 {@code users.tenant_id}）
 * @param roleCodes 业务角色码
 */
public record AuthenticatedUser(UUID userId, UUID tenantId, List<String> roleCodes) {
}
