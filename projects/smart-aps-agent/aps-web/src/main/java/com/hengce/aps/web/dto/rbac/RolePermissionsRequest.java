package com.hengce.aps.web.dto.rbac;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 角色权限替换请求（C30 / 25.1.5）。
 *
 * @param permissionCodes 目标权限码，空数组表示清空
 */
public record RolePermissionsRequest(@NotNull List<String> permissionCodes) {
}
