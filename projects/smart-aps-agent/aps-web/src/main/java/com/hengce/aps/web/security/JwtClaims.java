package com.hengce.aps.web.security;

import java.util.List;
import java.util.UUID;

/**
 * JWT 校验后的声明（C30）。
 *
 * @param userId {@code sub}
 * @param tenantId {@code tenantId}
 * @param roles {@code roles}
 * @param issuedAt 签发秒
 * @param expiresAt 过期秒
 */
public record JwtClaims(UUID userId, UUID tenantId, List<String> roles, long issuedAt, long expiresAt) {
}
