package com.hengce.aps.service.rbac;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 用户响应（C30 / 25.1.4），永不包含 {@code password_hash}。
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
 */
public record UserView(UUID id, String username, String displayName, String email, String phone,
                       String status, OffsetDateTime lastLoginAt, Integer version,
                       List<String> roleCodes) {
}
