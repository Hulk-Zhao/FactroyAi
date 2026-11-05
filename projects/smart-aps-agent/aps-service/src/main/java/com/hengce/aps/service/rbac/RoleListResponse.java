package com.hengce.aps.service.rbac;

import java.util.List;

/**
 * 角色列表响应（C30 / 25.1.4）。契约要求 {@code GET /roles} 返回 {@code {items:[...]}}。
 *
 * @param items 角色列表
 */
public record RoleListResponse(List<RoleView> items) {
}
