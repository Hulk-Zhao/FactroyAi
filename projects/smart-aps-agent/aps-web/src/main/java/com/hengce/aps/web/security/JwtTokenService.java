package com.hengce.aps.web.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.web.config.ApiSecurityProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * JWT HS256 签发与校验（C30 / 25.1.2）。
 *
 * <p>payload 固定包含 {@code sub}/{@code tenantId}/{@code roles}/{@code iat}/{@code exp}；
 * 有效期取 {@code aps.security.jwt.ttl-seconds}，默认 28800 秒，不提供 refresh。</p>
 */
@Component
public class JwtTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final String JWT_ALGORITHM = "HS256";

    private static final TypeReference<Map<String, Object>> JSON_MAP_TYPE = new TypeReference<>() {
    };

    private final ApiSecurityProperties properties;

    private final ObjectMapper objectMapper;

    /**
     * 构造服务。
     *
     * @param properties 安全配置
     * @param objectMapper JSON 序列化器
     */
    public JwtTokenService(ApiSecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 签发访问令牌。
     *
     * @param userId 用户主键（{@code sub}）
     * @param tenantId 租户
     * @param roles 角色集合（须含 {@code APS_USER}）
     * @return compact JWT
     */
    public String issue(UUID userId, UUID tenantId, List<String> roles) {
        long now = System.currentTimeMillis() / 1000L;
        long expiresAt = now + ttlSeconds();
        Map<String, Object> header = Map.of("alg", JWT_ALGORITHM, "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", userId.toString());
        payload.put("tenantId", tenantId.toString());
        payload.put("roles", roles);
        payload.put("iat", now);
        payload.put("exp", expiresAt);
        String encodedHeader = encode(header);
        String encodedPayload = encode(payload);
        String signingInput = encodedHeader + "." + encodedPayload;
        return signingInput + "." + sign(signingInput);
    }

    /**
     * 校验令牌；签名、算法、过期、sub/tenantId 任一不合法则返回 null。
     *
     * @param token compact JWT
     * @return 声明，非法时 null
     */
    public JwtClaims verify(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            Map<String, Object> header = decodeJson(parts[0]);
            if (header == null || !JWT_ALGORITHM.equals(header.get("alg"))) {
                return null;
            }
            String signingInput = parts[0] + "." + parts[1];
            byte[] expected = hmac(signingInput);
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                return null;
            }
            Map<String, Object> payload = decodeJson(parts[1]);
            if (payload == null) {
                return null;
            }
            Object exp = payload.get("exp");
            if (!(exp instanceof Number number) || number.longValue() < System.currentTimeMillis() / 1000L) {
                return null;
            }
            UUID userId = parseUuid(payload.get("sub"));
            UUID tenantId = parseUuid(payload.get("tenantId"));
            if (userId == null || tenantId == null) {
                return null;
            }
            List<String> roles = roles(payload.get("roles"));
            Object iat = payload.get("iat");
            long issuedAt = iat instanceof Number issued ? issued.longValue() : 0L;
            return new JwtClaims(userId, tenantId, roles, issuedAt, number(exp));
        } catch (RuntimeException | IOException | GeneralSecurityException exception) {
            return null;
        }
    }

    /**
     * 有效期（秒）。
     *
     * @return 秒
     */
    public long ttlSeconds() {
        return properties.getJwt().getTtlSeconds();
    }

    private String encode(Map<String, Object> value) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (IOException exception) {
            throw new IllegalStateException("JWT 序列化失败", exception);
        }
    }

    private Map<String, Object> decodeJson(String value) throws IOException {
        return objectMapper.readValue(Base64.getUrlDecoder().decode(value), JSON_MAP_TYPE);
    }

    private byte[] hmac(String input) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8),
                HMAC_ALGORITHM));
        return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String input) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(input));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("JWT 签名失败", exception);
        }
    }

    private static UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static List<String> roles(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
