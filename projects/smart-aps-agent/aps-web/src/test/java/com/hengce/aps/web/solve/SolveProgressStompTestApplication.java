package com.hengce.aps.web.solve;

import com.hengce.aps.web.config.ApiSecurityProperties;
import com.hengce.aps.web.config.SolveProgressWebSocketConfiguration;
import com.hengce.aps.web.controller.solve.SolveProgressStompBridge;
import com.hengce.aps.web.controller.solve.SolveProgressSubscriptionListener;
import com.hengce.aps.web.security.JwtTokenService;
import com.hengce.aps.web.security.SolveProgressWebSocketAuthInterceptor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * STOMP 单测的最小应用：只装配 WebSocket 配置、快照控制器与事件桥接器。
 *
 * <p>排除数据源/MyBatis 自动配置，避免没有真库时测试上下文启动失败。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
})
@Import({SolveProgressWebSocketConfiguration.class, SolveProgressSubscriptionListener.class,
        SolveProgressStompBridge.class, ApiSecurityProperties.class, JwtTokenService.class,
        SolveProgressWebSocketAuthInterceptor.class})
public class SolveProgressStompTestApplication {
}
