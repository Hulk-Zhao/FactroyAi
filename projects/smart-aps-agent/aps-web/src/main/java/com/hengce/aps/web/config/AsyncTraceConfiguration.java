package com.hengce.aps.web.config;

import com.hengce.aps.common.trace.TraceContext;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 异步上下文传递配置。
 *
 * <p>为 Spring Boot 默认 {@code applicationTaskExecutor} 注册 {@link TaskDecorator}，
 * 使 {@code @Async} 与求解回调线程继承 traceId、tenantId 等 MDC 字段，避免日志断链。</p>
 */
@Configuration
public class AsyncTraceConfiguration {

    /**
     * 线程池任务装饰器：提交时捕获上下文，执行时恢复，结束后清理。
     *
     * @return 任务装饰器
     */
    @Bean
    public TaskDecorator traceTaskDecorator() {
        return runnable -> {
            String traceId = TraceContext.getTraceId();
            Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
            return () -> {
                String previousTraceId = TraceContext.getTraceId();
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                TraceContext.setTraceId(traceId);
                if (capturedMdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(capturedMdc);
                }
                try {
                    runnable.run();
                } finally {
                    if (previousMdc == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previousMdc);
                    }
                    if (previousTraceId.isEmpty()) {
                        TraceContext.clear();
                    } else {
                        TraceContext.setTraceId(previousTraceId);
                    }
                }
            };
        };
    }
}
