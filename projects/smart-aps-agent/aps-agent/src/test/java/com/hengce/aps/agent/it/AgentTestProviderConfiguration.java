package com.hengce.aps.agent.it;

import com.hengce.aps.agent.llm.LlmProvider;
import com.hengce.aps.agent.testsupport.FakeLlmProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 真库测试专用假 provider（仅 src/test 作用域），用于在无外网环境下验证编排入库与续跑；
 * 生产 classpath 不包含本类。
 */
@Configuration(proxyBeanMethods = false)
public class AgentTestProviderConfiguration {

    /**
     * 名为 {@code fake} 的固定应答 provider，仅测试装配可见。
     *
     * @return 假 provider
     */
    @Bean
    public LlmProvider dbTestFakeProvider() {
        return FakeLlmProvider.replying("fake", "{\"answer\":\"db-test\"}");
    }
}
