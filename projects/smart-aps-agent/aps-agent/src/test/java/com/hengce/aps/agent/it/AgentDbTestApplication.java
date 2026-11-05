package com.hengce.aps.agent.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agent 真库集成测试的 Spring 装配入口：只扫描 {@code com.hengce.aps}，
 * 装配 Agent 框架与测试数据源，不引入 Web 与求解器。
 */
@SpringBootApplication(scanBasePackages = "com.hengce.aps")
public class AgentDbTestApplication {
}
