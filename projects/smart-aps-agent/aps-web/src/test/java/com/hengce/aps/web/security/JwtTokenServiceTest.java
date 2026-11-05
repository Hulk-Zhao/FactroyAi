package com.hengce.aps.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.web.config.ApiSecurityProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C30 JWT 签发、校验与过期测试。
 */
class JwtTokenServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000303");

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String SECRET = "hengce-aps-dev-secret-please-change-32bytes";

    private ApiSecurityProperties properties;

    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        properties = new ApiSecurityProperties();
        properties.getJwt().setSecret(SECRET);
        jwtTokenService = new JwtTokenService(properties, new ObjectMapper());
    }

    @Test
    @DisplayName("签发后可校验：sub/tenantId/roles 正确，TTL=28800s")
    void issueAndVerify() {
        String token = jwtTokenService.issue(USER_ID, TENANT_ID, List.of("PLANNER", "APS_USER"));

        JwtClaims claims = jwtTokenService.verify(token);

        assertEquals(USER_ID, claims.userId());
        assertEquals(TENANT_ID, claims.tenantId());
        assertEquals(List.of("PLANNER", "APS_USER"), claims.roles());
        assertEquals(28800L, claims.expiresAt() - claims.issuedAt());
        assertEquals(28800L, jwtTokenService.ttlSeconds());
    }

    @Test
    @DisplayName("签名被篡改时校验失败")
    void tamperedTokenRejected() {
        String token = jwtTokenService.issue(USER_ID, TENANT_ID, List.of("APS_USER"));

        assertNull(jwtTokenService.verify(token + "x"));
        assertNull(jwtTokenService.verify("not-a-jwt"));
        assertNull(jwtTokenService.verify(null));
    }

    @Test
    @DisplayName("过期令牌（exp 已过）校验失败")
    void expiredTokenRejected() {
        properties.getJwt().setTtlSeconds(-60L);
        String token = jwtTokenService.issue(USER_ID, TENANT_ID, List.of("APS_USER"));

        assertNull(jwtTokenService.verify(token));
    }

    @Test
    @DisplayName("密钥不一致时校验失败")
    void wrongSecretRejected() {
        String token = jwtTokenService.issue(USER_ID, TENANT_ID, List.of("APS_USER"));
        ApiSecurityProperties other = new ApiSecurityProperties();
        other.getJwt().setSecret("another-secret-at-least-32-bytes-long");
        JwtTokenService otherService = new JwtTokenService(other, new ObjectMapper());

        assertNull(otherService.verify(token));
    }

    @Test
    @DisplayName("角色的必需 APS_USER 由过滤器检查，令牌本身不伪造")
    void rolesPreserved() {
        String token = jwtTokenService.issue(USER_ID, TENANT_ID, List.of("VIEWER"));

        JwtClaims claims = jwtTokenService.verify(token);

        assertTrue(claims.roles().contains("VIEWER"));
        assertFalse(claims.roles().contains("APS_USER"));
    }
}
