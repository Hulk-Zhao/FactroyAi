package com.hengce.aps.web.security;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * STOMP 会话认证主体（P1-4）：承载 JWT {@code sub}/{@code tenantId}/{@code roles}。
 *
 * <p>作为 {@code Principal} 挂在 STOMP 会话上，供订阅授权与租户上下文注入使用；不暴露令牌原文。</p>
 *
 * @param userId 用户主键
 * @param tenantId 租户
 * @param roles 令牌角色
 */
public record StompAuthPrincipal(UUID userId, UUID tenantId, List<String> roles) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
