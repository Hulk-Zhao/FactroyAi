package com.hengce.aps.web.dto.rbac;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求（C30 / 25.1.2）。
 *
 * @param username 登录名
 * @param password 明文口令
 */
public record LoginRequest(@NotBlank @Size(max = 64) String username,
                           @NotBlank @Size(max = 128) String password) {
}
