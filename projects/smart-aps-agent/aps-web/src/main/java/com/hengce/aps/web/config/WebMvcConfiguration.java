package com.hengce.aps.web.config;

import com.hengce.aps.common.idempotency.IdempotencyStore;
import com.hengce.aps.service.rbac.PermissionService;
import com.hengce.aps.web.idempotency.IdempotencyInterceptor;
import com.hengce.aps.web.idempotency.InMemoryIdempotencyStore;
import com.hengce.aps.web.security.PermissionInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册幂等拦截器。
 */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final PermissionService permissionService;

    /**
     * 构造配置。
     *
     * @param permissionService 权限服务
     */
    public WebMvcConfiguration(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * MVP 幂等键存储；接入 Redis 后替换本 Bean 即可。
     *
     * @return 幂等键存储
     */
    @Bean
    public IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    /**
     * 幂等拦截器 Bean。
     *
     * @param idempotencyStore 幂等键存储
     * @return 拦截器
     */
    @Bean
    public IdempotencyInterceptor idempotencyInterceptor(IdempotencyStore idempotencyStore) {
        return new IdempotencyInterceptor(idempotencyStore);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor(permissionService))
                .addPathPatterns("/api/**")
                .order(0);
        registry.addInterceptor(idempotencyInterceptor(idempotencyStore()))
                .addPathPatterns("/api/**")
                .order(1)
                // C33 §25.5：下发要求重复 Idempotency-Key 返回首次 dispatchId（200），
                // 因此由 PlanDispatchService 以确定性 dispatchId + outbox 唯一索引实现幂等，
                // 不走全局拦截器的「重复 key → 40900」语义。
                // C45：Agent 事件上报要求重复 Idempotency-Key 返回既有审计行（duplicate=true），
                // 由 AgentEventService 在 audit_log 咨询锁保护下实现，同样不走 40900。
                .excludePathPatterns("/api/v1/plans/*/dispatch", "/api/v1/agent/events");
    }

    /**
     * 权限拦截器 Bean（C30）。
     *
     * @param permissionService 权限服务
     * @return 拦截器
     */
    @Bean
    public PermissionInterceptor permissionInterceptor(PermissionService permissionService) {
        return new PermissionInterceptor(permissionService);
    }
}
