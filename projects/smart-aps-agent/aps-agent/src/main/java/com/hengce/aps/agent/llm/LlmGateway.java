package com.hengce.aps.agent.llm;

import com.hengce.aps.agent.config.ApsAgentProperties;
import com.hengce.aps.agent.config.LlmEndpointResolver;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 网关：唯一对业务层暴露的调用入口，<b>永不抛出</b>。
 *
 * <p>成功返回 {@link LlmResult#available()} 为 true；任何未配置、不可用、
 * 超时、错误、空输出、结构化校验失败都以 {@link LlmResult#degraded} 返回，
 * 并记录 {@code event=agent.llm.degraded} 日志。日志与 detail 均不含密钥与 URL。</p>
 */
public final class LlmGateway {

    private static final Logger LOG = LoggerFactory.getLogger(LlmGateway.class);

    private static final int DETAIL_LIMIT = 160;

    private final LlmProviderFactory factory;

    private final ApsAgentProperties.Llm llmProperties;

    private final String configuredProvider;

    /**
     * 构造网关。
     *
     * @param factory provider 注册表
     * @param properties Agent 配置（读取当前 provider）
     */
    public LlmGateway(LlmProviderFactory factory, ApsAgentProperties properties) {
        this.factory = factory;
        this.llmProperties = properties.getLlm();
        this.configuredProvider = properties.getLlm().getProvider();
    }

    /**
     * 当前配置的 provider 名。
     *
     * @return provider 名，可能为空
     */
    public String configuredProvider() {
        return configuredProvider;
    }

    /**
     * 当前配置实际生效的模型名（用于 {@code agent_run} 摘要，不含密钥）。
     *
     * @return 模型名；未配置为空串
     */
    public String configuredModel() {
        return LlmEndpointResolver.resolve(llmProperties, configuredProvider).model();
    }

    /**
     * 当前配置是否完整（地址/模型与必要的密钥齐备）。
     *
     * @return true 表示可以发起调用
     */
    public boolean configured() {
        String name = normalize(configuredProvider);
        return !name.isEmpty() && !DisabledLlmProvider.NAME.equals(name)
                && LlmEndpointResolver.resolve(llmProperties, name).complete();
    }

    /**
     * 使用当前配置的 provider 调用。
     *
     * @param request 请求
     * @return 成功或降级结果
     */
    public LlmResult call(LlmRequest request) {
        return call(configuredProvider, request);
    }

    /**
     * 使用指定 provider 调用，用于「同 Prompt 切换 provider」验证。
     *
     * @param providerName provider 名
     * @param request 请求
     * @return 成功或降级结果
     */
    public LlmResult call(String providerName, LlmRequest request) {
        String name = providerName == null ? "" : providerName.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return degrade(name, LlmDegradationReason.NO_PROVIDER_CONFIGURED,
                    "missing: " + LlmEndpointResolver.ENV_PROVIDER
                            + " (aps.agent.llm.provider is empty)", 0L);
        }
        if (DisabledLlmProvider.NAME.equals(name)) {
            return degrade(name, LlmDegradationReason.NO_PROVIDER_CONFIGURED,
                    "missing: " + LlmEndpointResolver.ENV_PROVIDER
                            + " (provider=none, llm not configured)", 0L);
        }
        Optional<LlmProvider> candidate = factory.find(name);
        if (candidate.isEmpty()) {
            return degrade(name, LlmDegradationReason.NO_PROVIDER_CONFIGURED,
                    "unknown provider: " + safe(name) + ", registered: " + factory.names(), 0L);
        }
        LlmProvider provider = candidate.get();
        if (!provider.available()) {
            LlmEndpointResolver.Resolved resolved = LlmEndpointResolver.resolve(llmProperties, name);
            String missing = resolved.missingKeys().isEmpty()
                    ? "provider reported unavailable" : "missing: " + String.join(", ", resolved.missingKeys());
            return degrade(name, LlmDegradationReason.NO_PROVIDER_CONFIGURED, missing, 0L);
        }
        long started = System.currentTimeMillis();
        try {
            LlmResponse response = provider.complete(request);
            long latency = response.latencyMillis() > 0
                    ? response.latencyMillis() : System.currentTimeMillis() - started;
            if (response.text() == null || response.text().isBlank()) {
                return degrade(name, LlmDegradationReason.EMPTY_RESPONSE,
                        "provider returned empty content", latency);
            }
            LOG.debug("event=agent.llm.succeeded provider={} latencyMs={} promptTokens={} completionTokens={}",
                    name, latency, response.promptTokens(), response.completionTokens());
            return LlmResult.success(name, response);
        } catch (LlmCallException exception) {
            long latency = System.currentTimeMillis() - started;
            LlmDegradationReason reason;
            if (exception.isTimeout()) {
                reason = LlmDegradationReason.TIMEOUT;
            } else if (exception.isUnavailable()) {
                reason = LlmDegradationReason.PROVIDER_UNAVAILABLE;
            } else {
                reason = LlmDegradationReason.PROVIDER_ERROR;
            }
            return degrade(name, reason, exception.getMessage(), latency);
        } catch (RuntimeException exception) {
            long latency = System.currentTimeMillis() - started;
            return degrade(name, LlmDegradationReason.PROVIDER_ERROR,
                    "unexpected error: " + exception.getClass().getSimpleName(), latency);
        }
    }

    private LlmResult degrade(String name, LlmDegradationReason reason, String detail, long latency) {
        String safeDetail = excerpt(detail);
        LOG.warn("event=agent.llm.degraded provider={} reason={} latencyMs={} detail={}",
                name, reason, latency, safeDetail);
        return LlmResult.degraded(name, reason, safeDetail, latency);
    }

    private static String excerpt(String detail) {
        if (detail == null) {
            return "";
        }
        String flattened = detail.replaceAll("[\\r\\n\\t]", " ").trim();
        return flattened.length() <= DETAIL_LIMIT ? flattened : flattened.substring(0, DETAIL_LIMIT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
