package com.hengce.aps.service.rbac;

import java.util.UUID;

/**
 * 权限点响应（C30）。
 *
 * @param id 权限主键
 * @param code 权限码
 * @param name 名称
 * @param module 模块
 * @param description 描述
 */
public record PermissionView(UUID id, String code, String name, String module, String description) {
}
