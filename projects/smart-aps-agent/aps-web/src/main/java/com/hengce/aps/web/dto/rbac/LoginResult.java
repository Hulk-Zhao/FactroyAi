package com.hengce.aps.web.dto.rbac;

import java.util.UUID;

/**
 * 登录响应（C30 / 25.1.2）。
 *
 * @param accessToken JWT
 * @param tokenType 固定 Bearer
 * @param expiresIn 有效期秒数
 * @param userId 用户主键
 */
public record LoginResult(String accessToken, String tokenType, long expiresIn, UUID userId) {
}
