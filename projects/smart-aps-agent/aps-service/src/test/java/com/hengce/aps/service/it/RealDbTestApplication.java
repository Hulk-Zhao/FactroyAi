package com.hengce.aps.service.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 真库集成测试的 Spring 装配入口。
 *
 * <p>只扫描 {@code com.hengce.aps}（aps-service 测试类路径上可见 common/domain/infrastructure/service），
 * 不装配 Web 层与求解器实现；持久层配置与生产运行完全一致：
 * {@code MybatisPlusConfiguration}（拦截器顺序）与 {@code MybatisTypeHandlerConfiguration}
 * （UUID/Serializable 全局 TypeHandler）均由组件扫描装配。</p>
 */
@SpringBootApplication(scanBasePackages = "com.hengce.aps")
public class RealDbTestApplication {
}
