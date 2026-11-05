package com.hengce.aps.service.rbac;

import java.util.List;
import java.util.UUID;

/**
 * 角色响应（C30）。
 *
 * @param id 角色主键
 * @param code 角色码
 * @param name 名称
 * @param description 描述
 * @param permissionCodes 权限码
 * @param version 乐观锁版本
 */
public record RoleView(UUID id, String code, String name, String description,
                       List<String> permissionCodes, Integer version) {
}
