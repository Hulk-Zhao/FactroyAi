package com.hengce.aps.service.rbac;

import java.util.List;

/**
 * {@code GET /me} 响应（C30）：用户信息 + 当前用户权限码集合。
 *
 * @param id 用户主键
 * @param username 登录名
 * @param displayName 显示名
 * @param email 邮箱
 * @param phone 手机号
 * @param status 状态
 * @param lastLoginAt 最近登录时间
 * @param version 乐观锁版本
 * @param roleCodes 角色码
 * @param permissions 权限码并集
 */
public record MeView(java.util.UUID id, String username, String displayName, String email, String phone,
                     String status, java.time.OffsetDateTime lastLoginAt, Integer version,
                     List<String> roleCodes, List<String> permissions) {
}
