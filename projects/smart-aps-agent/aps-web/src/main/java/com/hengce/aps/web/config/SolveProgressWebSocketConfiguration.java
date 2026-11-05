package com.hengce.aps.web.config;

import com.hengce.aps.web.security.SolveProgressWebSocketAuthInterceptor;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 求解进度 STOMP 配置（M2-SE-03 / P1-4）。
 *
 * <p>连接端点 {@code /ws/solve-tasks/{id}}（兼容无路径参数形态），订阅
 * {@code /topic/solve-tasks/{id}}；使用原生 WebSocket，不启用 SockJS。
 * Origin 收敛为 {@code aps.security.websocket.allowed-origins} 白名单（默认仅本地前端），
 * 入站消息统一经过 {@link SolveProgressWebSocketAuthInterceptor} 做 JWT 与订阅归属校验。</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class SolveProgressWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolveProgressWebSocketConfiguration.class);

    private final ApiSecurityProperties properties;

    private final SolveProgressWebSocketAuthInterceptor authInterceptor;

    /**
     * 构造配置。
     *
     * @param properties 安全配置
     * @param authInterceptor STOMP 鉴权拦截器
     */
    public SolveProgressWebSocketConfiguration(ApiSecurityProperties properties,
                                               SolveProgressWebSocketAuthInterceptor authInterceptor) {
        this.properties = properties;
        this.authInterceptor = authInterceptor;
    }

    /**
     * 启动时打印 Origin 白名单（不含密钥）。
     */
    @PostConstruct
    public void logAllowedOrigins() {
        LOGGER.info("WebSocket Origin 白名单: {}", properties.getWebsocket().getAllowedOrigins());
    }

    /**
     * 注册 STOMP 端点：精确路径与带任务 ID 的路径均映射到同一处理器。
     *
     * @param registry 端点注册表
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/solve-tasks", "/ws/solve-tasks/**")
                .setAllowedOriginPatterns(allowedOriginPatterns());
    }

    /**
     * 启用简单内存 Broker，仅广播 {@code /topic} 前缀。
     *
     * @param registry Broker 注册表
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    /**
     * 入站消息鉴权：CONNECT 校验 JWT，SUBSCRIBE 校验任务租户归属。
     *
     * @param registration 入站通道注册
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    private String[] allowedOriginPatterns() {
        List<String> patterns = properties.getWebsocket().getAllowedOrigins();
        if (patterns == null) {
            return new String[0];
        }
        return patterns.stream().map(String::trim).filter(pattern -> !pattern.isBlank()).toArray(String[]::new);
    }
}
