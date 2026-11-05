package com.hengce.aps.web.dto.rbac;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 创建用户请求（C30 / 25.1.4）。
 *
 * @param username 登录名
 * @param displayName 显示名
 * @param email 邮箱
 * @param phone 手机号
 * @param password 明文口令
 * @param roleCodes 角色码
 */
public record UserCreateRequest(@NotBlank @Size(max = 64) String username,
                                @Size(max = 128) String displayName,
                                @Email @Size(max = 128) String email,
                                @Size(max = 32) String phone,
                                @NotBlank @Size(max = 128) String password,
                                List<String> roleCodes) {
}
