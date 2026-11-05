package com.hengce.aps.web.security;

import java.util.UUID;

/**
 * 请求线程内的认证主体（C30）。
 *
 * @param userId 用户主键
 * @param roles 令牌角色（含 APS_USER）
 * @param adminEquivalent true 表示 dev API Key 等价 ADMIN
 * @param mechanism 认证方式
 */
public record AuthPrincipal(UUID userId, java.util.List<String> roles, boolean adminEquivalent,
                            AuthMechanism mechanism) {
}
