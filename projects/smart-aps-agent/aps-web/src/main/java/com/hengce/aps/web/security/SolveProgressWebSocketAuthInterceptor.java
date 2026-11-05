package com.hengce.aps.web.security;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.schedule.SolveTaskLifecycleService;
import com.hengce.aps.web.config.ApiSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * 求解进度 STOMP 鉴权拦截器（P1-4）。
 *
 * <p>CONNECT 必须携带 {@code Authorization: Bearer <JWT>}，校验签名/过期/必需角色；
 * SUBSCRIBE 仅允许 {@code /topic/solve-tasks/{taskId}}，并在显式租户上下文下校验任务归属，
 * 非本租户任务直接拒绝订阅。会话主体（含租户）挂在 STOMP {@link java.security.Principal} 上，
 * 供后续消息注入 {@link TenantContext}，避免落默认租户。</p>
 */
@Component
public class SolveProgressWebSocketAuthInterceptor implements ChannelInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolveProgressWebSocketAuthInterceptor.class);

    /**
     * 求解进度主题前缀。
     */
    public static final String TOPIC_PREFIX = "/topic/solve-tasks/";

    /**
     * 会话属性键：认证主体副本，供事件监听器在非拦截器线程读取。
     */
    public static final String SESSION_AUTH_ATTRIBUTE = "aps.ws.auth.principal";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;

    private final ApiSecurityProperties properties;

    private final SolveTaskLifecycleService lifecycleService;

    /**
     * 构造拦截器。
     *
     * @param jwtTokenService JWT 校验服务
     * @param properties 安全配置（必需角色）
     * @param lifecycleService 求解任务生命周期（任务归属校验）
     */
    public SolveProgressWebSocketAuthInterceptor(JwtTokenService jwtTokenService,
                                                 ApiSecurityProperties properties,
                                                 SolveTaskLifecycleService lifecycleService) {
        this.jwtTokenService = jwtTokenService;
        this.properties = properties;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            StompAuthPrincipal principal = authenticate(accessor);
            accessor.setUser(principal);
            java.util.Map<String, Object> attributes = accessor.getSessionAttributes();
            if (attributes != null) {
                attributes.put(SESSION_AUTH_ATTRIBUTE, principal);
            }
            return message;
        }
        if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscribe(accessor);
        }
        return message;
    }

    private StompAuthPrincipal authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER);
        if (authorization == null) {
            authorization = accessor.getFirstNativeHeader("authorization");
        }
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new MessageDeliveryException("WebSocket 连接未认证：请在 CONNECT 帧携带 Authorization: Bearer <JWT>");
        }
        JwtClaims claims = jwtTokenService.verify(authorization.substring(BEARER_PREFIX.length()).trim());
        if (claims == null) {
            throw new MessageDeliveryException("WebSocket 连接认证失败：令牌无效或已过期");
        }
        String requiredRole = properties.getJwt().getRequiredRole();
        if (!claims.roles().contains(requiredRole)) {
            throw new MessageDeliveryException("WebSocket 连接认证失败：令牌缺少必需角色");
        }
        StompAuthPrincipal principal = new StompAuthPrincipal(claims.userId(), claims.tenantId(), claims.roles());
        LOGGER.info("WebSocket 认证通过 userId={} tenantId={}", claims.userId(), claims.tenantId());
        return principal;
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(TOPIC_PREFIX)) {
            throw new MessageDeliveryException("WebSocket 订阅被拒绝：仅允许订阅 " + TOPIC_PREFIX + "{taskId}");
        }
        StompAuthPrincipal principal = principalOf(accessor);
        if (principal == null) {
            throw new MessageDeliveryException("WebSocket 订阅被拒绝：会话未认证");
        }
        long taskId;
        try {
            taskId = Long.parseLong(destination.substring(TOPIC_PREFIX.length()));
        } catch (NumberFormatException exception) {
            throw new MessageDeliveryException("WebSocket 订阅被拒绝：taskId 不是合法数字");
        }
        TenantContext.setTenantId(principal.tenantId());
        try {
            lifecycleService.detail(taskId);
        } catch (BizException exception) {
            LOGGER.warn("WebSocket 跨租户订阅被拒绝 userId={} tenantId={} taskId={}",
                    principal.userId(), principal.tenantId(), taskId);
            throw new MessageDeliveryException("WebSocket 订阅被拒绝：任务不存在或不属于当前租户");
        } finally {
            TenantContext.clear();
        }
        LOGGER.info("WebSocket 订阅授权通过 userId={} tenantId={} taskId={}",
                principal.userId(), principal.tenantId(), taskId);
    }

    private StompAuthPrincipal principalOf(StompHeaderAccessor accessor) {
        return principalOf(accessor.getUser(), accessor.getSessionAttributes());
    }

    /**
     * 解析 STOMP 会话认证主体（优先 Principal，回退会话属性）。
     *
     * @param user STOMP 会话主体
     * @param attributes 会话属性
     * @return 认证主体；非本鉴权体系返回 null
     */
    public static StompAuthPrincipal principalOf(java.security.Principal user,
                                                 java.util.Map<String, Object> attributes) {
        if (user instanceof StompAuthPrincipal principal) {
            return principal;
        }
        Object attribute = attributes == null ? null : attributes.get(SESSION_AUTH_ATTRIBUTE);
        return attribute instanceof StompAuthPrincipal principal ? principal : null;
    }

}
