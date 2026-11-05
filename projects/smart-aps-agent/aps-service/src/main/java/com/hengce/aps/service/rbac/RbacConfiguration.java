package com.hengce.aps.service.rbac;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * RBAC 基础设施 Bean（C30）。
 */
@Configuration
public class RbacConfiguration {

    /**
     * bcrypt 口令编码器（cost=10）。
     *
     * @return 编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 可注入时钟，便于锁定窗口测试。
     *
     * @return UTC 系统时钟
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
