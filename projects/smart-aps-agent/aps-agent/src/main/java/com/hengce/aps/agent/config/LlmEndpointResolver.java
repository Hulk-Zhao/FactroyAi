package com.hengce.aps.agent.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解析 LLM 单次调用实际生效的地址、密钥、模型与超时。
 *
 * <p><b>优先级固定为：环境变量 &gt; application.yml &gt; 缺省。</b>
 * 环境变量 {@code APS_AGENT_LLM_BASE_URL / API_KEY / MODEL / TIMEOUT_SECONDS}
 * 绑定到 {@code aps.agent.llm.*} 扁平配置；非空时覆盖
 * {@code aps.agent.llm.deepseek|qwen|ollama} 下同名的端点配置。
 * 未配置时保持 {@code provider=none}，不发起任何网络调用。</p>
 *
 * <p>本类只做字符串解析，不读取、不记录、不拼接真实密钥。</p>
 */
public final class LlmEndpointResolver {

    /**
     * provider 环境变量键。
     */
    public static final String ENV_PROVIDER = "APS_AGENT_LLM_PROVIDER";

    /**
     * 基础地址环境变量键。
     */
    public static final String ENV_BASE_URL = "APS_AGENT_LLM_BASE_URL";

    /**
     * 密钥环境变量键。
     */
    public static final String ENV_API_KEY = "APS_AGENT_LLM_API_KEY";

    /**
     * 模型环境变量键。
     */
    public static final String ENV_MODEL = "APS_AGENT_LLM_MODEL";

    /**
     * 超时秒数环境变量键。
     */
    public static final String ENV_TIMEOUT_SECONDS = "APS_AGENT_LLM_TIMEOUT_SECONDS";

    /**
     * 未显式配置超时时的缺省值（10 秒）。
     */
    public static final long DEFAULT_TIMEOUT_MILLIS = 10000L;

    private LlmEndpointResolver() {
    }

    /**
     * 解析指定 provider 的生效配置。
     *
     * @param llm LLM 配置
     * @param provider provider 名
     * @return 生效配置；{@code missingKeys} 为空表示配置完整
     */
    public static Resolved resolve(ApsAgentProperties.Llm llm, String provider) {
        String name = normalize(provider);
        ApsAgentProperties.Endpoint endpoint = endpoint(llm, name);
        String baseUrl = firstNonBlank(llm.getBaseUrl(), endpoint == null ? "" : endpoint.getBaseUrl());
        String apiKey = firstNonBlank(llm.getApiKey(), endpoint == null ? "" : endpoint.getApiKey());
        String model = firstNonBlank(llm.getModel(), endpoint == null ? "" : endpoint.getModel());
        List<String> missing = new ArrayList<>();
        if (baseUrl.isBlank()) {
            missing.add(ENV_BASE_URL);
        }
        if (model.isBlank()) {
            missing.add(ENV_MODEL);
        }
        if (!"ollama".equals(name) && apiKey.isBlank()) {
            missing.add(ENV_API_KEY);
        }
        return new Resolved(name, baseUrl, apiKey, model, timeoutMillis(llm), List.copyOf(missing));
    }

    private static long timeoutMillis(ApsAgentProperties.Llm llm) {
        Long seconds = llm.getTimeoutSeconds();
        if (seconds != null && seconds > 0) {
            return seconds * 1000L;
        }
        long millis = llm.getTimeoutMillis();
        return millis > 0 ? millis : DEFAULT_TIMEOUT_MILLIS;
    }

    private static ApsAgentProperties.Endpoint endpoint(ApsAgentProperties.Llm llm, String name) {
        return switch (name) {
            case "deepseek" -> llm.getDeepseek();
            case "qwen" -> llm.getQwen();
            case "ollama" -> llm.getOllama();
            default -> null;
        };
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private static String normalize(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 生效配置。
     *
     * @param provider 规范化后的 provider 名
     * @param baseUrl 基础地址
     * @param apiKey 密钥（仅用于发请求，禁止落日志/响应）
     * @param model 模型名
     * @param timeoutMillis 超时毫秒
     * @param missingKeys 缺失项对应的环境变量键；空表示完整
     */
    public record Resolved(String provider, String baseUrl, String apiKey, String model,
                           long timeoutMillis, List<String> missingKeys) {

        /**
         * 配置是否完整。
         *
         * @return true 表示地址/模型（非 Ollama 还有密钥）齐备
         */
        public boolean complete() {
            return missingKeys.isEmpty();
        }
    }
}
