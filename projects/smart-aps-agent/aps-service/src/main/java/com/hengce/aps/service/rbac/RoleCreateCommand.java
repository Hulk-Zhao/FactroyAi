package com.hengce.aps.service.rbac;

import java.util.List;

/**
 * 创建角色命令（C30）。
 *
 * @param code 角色码（大写）
 * @param name 名称
 * @param description 描述
 * @param permissionCodes 初始权限码
 */
public record RoleCreateCommand(String code, String name, String description, List<String> permissionCodes) {
}
