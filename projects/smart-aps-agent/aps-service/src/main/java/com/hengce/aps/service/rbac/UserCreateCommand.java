package com.hengce.aps.service.rbac;

import java.util.List;

/**
 * 创建用户命令（C30）。
 *
 * @param username 登录名
 * @param displayName 显示名
 * @param email 邮箱
 * @param phone 手机号
 * @param password 明文口令，服务层立即 bcrypt
 * @param roleCodes 初始角色码
 */
public record UserCreateCommand(String username, String displayName, String email, String phone,
                                String password, List<String> roleCodes) {
}
