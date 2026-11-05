package com.hengce.aps.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.agent.checkpoint.CheckpointStore;
import com.hengce.aps.agent.checkpoint.JdbcCheckpointStore;
import com.hengce.aps.agent.llm.DeepSeekLlmProvider;
import com.hengce.aps.agent.llm.DisabledLlmProvider;
import com.hengce.aps.agent.llm.LlmGateway;
import com.hengce.aps.agent.llm.LlmProvider;
import com.hengce.aps.agent.llm.LlmProviderFactory;
import com.hengce.aps.agent.llm.OllamaLlmProvider;
import com.hengce.aps.agent.llm.QwenLlmProvider;
import com.hengce.aps.agent.orchestration.AgentOrchestrator;
import com.hengce.aps.agent.orchestration.AgentStep;
import com.hengce.aps.agent.orchestration.CallLlmStep;
import com.hengce.aps.agent.orchestration.ComposePromptStep;
import com.hengce.aps.agent.orchestration.ContextSource;
import com.hengce.aps.agent.orchestration.EmptyContextSource;
import com.hengce.aps.agent.orchestration.LoadContextStep;
import com.hengce.aps.agent.orchestration.ValidateOutputStep;
import com.hengce.aps.agent.prompt.JdbcPromptStore;
import com.hengce.aps.agent.prompt.PromptStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Agent 框架装配：provider、网关、Prompt/检查点存储与最小编排骨架。
 *
 * <p>所有 provider 均真实 HTTP 实现，但默认 {@code provider=none}，无凭证环境不会发起任何网络调用。
 * 业务车道可通过提供自己的 {@link ContextSource} / {@link PromptStore} / {@link CheckpointStore}
 * 覆盖默认实现，无需修改本类。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApsAgentProperties.class)
@ConditionalOnProperty(prefix = "aps.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(AgentConfiguration.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * DeepSeek provider；未配置 url/key 时 available=false，不会发起调用。
     *
     * @param properties Agent 配置
     * @return provider
     */
    @Bean
    public LlmProvider deepSeekLlmProvider(ApsAgentProperties properties) {
        LlmEndpointResolver.Resolved resolved =
                LlmEndpointResolver.resolve(properties.getLlm(), DeepSeekLlmProvider.NAME);
        return new DeepSeekLlmProvider(resolved.baseUrl(), resolved.apiKey(), resolved.model(),
                resolved.timeoutMillis(), JSON);
    }

    /**
     * 通义（DashScope 兼容模式）provider；未配置 url/key 时 available=false。
     *
     * @param properties Agent 配置
     * @return provider
     */
    @Bean
    public LlmProvider qwenLlmProvider(ApsAgentProperties properties) {
        LlmEndpointResolver.Resolved resolved =
                LlmEndpointResolver.resolve(properties.getLlm(), QwenLlmProvider.NAME);
        return new QwenLlmProvider(resolved.baseUrl(), resolved.apiKey(), resolved.model(),
                resolved.timeoutMillis(), JSON);
    }

    /**
     * 本地 Ollama provider；默认地址为 {@code http://127.0.0.1:11434}，不需要 key。
     *
     * @param properties Agent 配置
     * @return provider
     */
    @Bean
    public LlmProvider ollamaLlmProvider(ApsAgentProperties properties) {
        LlmEndpointResolver.Resolved resolved =
                LlmEndpointResolver.resolve(properties.getLlm(), OllamaLlmProvider.NAME);
        return new OllamaLlmProvider(resolved.baseUrl(), resolved.model(),
                resolved.timeoutMillis(), JSON);
    }

    /**
     * 显式关闭实现位。
     *
     * @return disabled provider
     */
    @Bean
    public LlmProvider disabledLlmProvider() {
        return new DisabledLlmProvider();
    }

    /**
     * provider 注册表。
     *
     * @param providers 容器内全部 provider
     * @return 注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public LlmProviderFactory llmProviderFactory(List<LlmProvider> providers) {
        return new LlmProviderFactory(providers);
    }

    /**
     * 门面网关；业务层只依赖本 Bean。
     *
     * @param factory provider 注册表
     * @param properties Agent 配置
     * @return 网关
     */
    @Bean
    @ConditionalOnMissingBean
    public LlmGateway llmGateway(LlmProviderFactory factory, ApsAgentProperties properties) {
        LlmGateway gateway = new LlmGateway(factory, properties);
        LOG.info("event=agent.llm.configured provider={}", gateway.configuredProvider());
        return gateway;
    }

    /**
     * Prompt 版本存储（V13 表 {@code agent_prompt_version}）。
     *
     * @param jdbcTemplate JDBC 模板
     * @return 存储
     */
    @Bean
    @ConditionalOnMissingBean
    public PromptStore promptStore(JdbcTemplate jdbcTemplate) {
        return new JdbcPromptStore(jdbcTemplate);
    }

    /**
     * 检查点存储（V13 表 {@code agent_run} / {@code agent_checkpoint}）。
     *
     * @param jdbcTemplate JDBC 模板
     * @return 存储
     */
    @Bean
    @ConditionalOnMissingBean
    public CheckpointStore checkpointStore(JdbcTemplate jdbcTemplate) {
        return new JdbcCheckpointStore(jdbcTemplate);
    }

    /**
     * 默认空上下文；业务车道提供自己的实现后本 Bean 自动让位。
     *
     * @return 上下文来源
     */
    @Bean
    @ConditionalOnMissingBean
    public ContextSource contextSource() {
        return new EmptyContextSource();
    }

    /**
     * 最小编排骨架：取数 → 组装 Prompt → 调 provider → 结构化校验 → 逐步检查点。
     *
     * @param checkpointStore 检查点存储
     * @param promptStore Prompt 版本存储
     * @param llmGateway LLM 网关
     * @param contextSource 上下文来源
     * @param properties Agent 配置
     * @return 编排器
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentOrchestrator agentOrchestrator(CheckpointStore checkpointStore, PromptStore promptStore,
                                               LlmGateway llmGateway, ContextSource contextSource,
                                               ApsAgentProperties properties) {
        List<AgentStep> steps = List.of(
                new LoadContextStep(contextSource),
                new ComposePromptStep(promptStore, properties.getPrompt().getDefaultContent()),
                new CallLlmStep(llmGateway, properties.getLlm().getMaxTokens()),
                new ValidateOutputStep(JSON));
        return new AgentOrchestrator(checkpointStore, steps, JSON,
                properties.getPrompt().getDefaultKey(), properties.getCheckpoint().isEnabled());
    }
}
