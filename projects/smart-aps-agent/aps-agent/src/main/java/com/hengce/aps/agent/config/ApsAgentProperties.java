package com.hengce.aps.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 框架配置（前缀 {@code aps.agent}）。
 *
 * <p>所有凭证只从环境变量占位符注入（yaml 中形如 {@code ${DS_KEY:}}），
 * 本类只承载字段，不读取、不拼接、不打印任何密钥。</p>
 */
@ConfigurationProperties(prefix = "aps.agent")
public class ApsAgentProperties {

    /**
     * 总开关；关闭时不装配任何 Agent bean，也不影响既有业务。
     */
    private boolean enabled = true;

    private final Llm llm = new Llm();

    private final Prompt prompt = new Prompt();

    private final Checkpoint checkpoint = new Checkpoint();

    /**
     * 读取总开关。
     *
     * @return true 表示启用 Agent 框架
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置总开关。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 读取 LLM 配置。
     *
     * @return LLM 配置
     */
    public Llm getLlm() {
        return llm;
    }

    /**
     * 读取 Prompt 配置。
     *
     * @return Prompt 配置
     */
    public Prompt getPrompt() {
        return prompt;
    }

    /**
     * 读取检查点配置。
     *
     * @return 检查点配置
     */
    public Checkpoint getCheckpoint() {
        return checkpoint;
    }

    /**
     * LLM 可插拔配置。
     */
    public static class Llm {

        /**
         * 当前 provider 名：{@code deepseek} / {@code qwen} / {@code ollama} / {@code none}。
         *
         * <p>环境变量 {@code APS_AGENT_LLM_PROVIDER} 优先于 yaml 中的同名项，缺省 {@code none}。</p>
         */
        private String provider = "none";

        /**
         * 统一基础地址（环境变量 {@code APS_AGENT_LLM_BASE_URL}）。
         *
         * <p>非空时覆盖 {@code deepseek/qwen/ollama} 各端点下的 {@code base-url}；
         * 优先级：环境变量 &gt; yaml &gt; 缺省。</p>
         */
        private String baseUrl = "";

        /**
         * 统一密钥（环境变量 {@code APS_AGENT_LLM_API_KEY}）；Ollama 允许为空。
         *
         * <p>非空时覆盖各端点下的 {@code api-key}；任何日志/响应/异常都不得包含该值。</p>
         */
        private String apiKey = "";

        /**
         * 统一模型名（环境变量 {@code APS_AGENT_LLM_MODEL}）。
         *
         * <p>非空时覆盖各端点下的 {@code model}。</p>
         */
        private String model = "";

        /**
         * 调用超时秒数（环境变量 {@code APS_AGENT_LLM_TIMEOUT_SECONDS}）；null 表示沿用
         * {@link #timeoutMillis}（缺省 10 秒）。
         */
        private Long timeoutSeconds;

        /**
         * 单次调用超时（毫秒）。
         */
        private long timeoutMillis = 10000L;

        /**
         * 单次调用默认输出上限（token）。
         */
        private int maxTokens = 512;

        private final Endpoint deepseek = new Endpoint();

        private final Endpoint qwen = new Endpoint();

        private final Endpoint ollama = new Endpoint();

        /**
         * 读取当前 provider 名。
         *
         * @return provider 名
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 设置当前 provider 名。
         *
         * @param provider provider 名
         */
        public void setProvider(String provider) {
            this.provider = provider;
        }

        /**
         * 读取统一基础地址。
         *
         * @return 基础地址；未配置为空串
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * 设置统一基础地址。
         *
         * @param baseUrl 基础地址
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * 读取统一密钥；调用方不得将其写入日志或响应。
         *
         * @return 密钥；未配置为空串
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * 设置统一密钥。
         *
         * @param apiKey 密钥
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * 读取统一模型名。
         *
         * @return 模型名；未配置为空串
         */
        public String getModel() {
            return model;
        }

        /**
         * 设置统一模型名。
         *
         * @param model 模型名
         */
        public void setModel(String model) {
            this.model = model;
        }

        /**
         * 读取超时秒数。
         *
         * @return 超时秒数；null 表示未显式配置
         */
        public Long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        /**
         * 设置超时秒数。
         *
         * @param timeoutSeconds 超时秒数
         */
        public void setTimeoutSeconds(Long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        /**
         * 读取超时毫秒数。
         *
         * @return 超时毫秒数
         */
        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        /**
         * 设置超时毫秒数。
         *
         * @param timeoutMillis 超时毫秒数
         */
        public void setTimeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        /**
         * 读取默认输出上限。
         *
         * @return token 上限
         */
        public int getMaxTokens() {
            return maxTokens;
        }

        /**
         * 设置默认输出上限。
         *
         * @param maxTokens token 上限
         */
        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        /**
         * 读取 DeepSeek 端点配置。
         *
         * @return 端点配置
         */
        public Endpoint getDeepseek() {
            return deepseek;
        }

        /**
         * 读取通义（DashScope 兼容模式）端点配置。
         *
         * @return 端点配置
         */
        public Endpoint getQwen() {
            return qwen;
        }

        /**
         * 读取 Ollama 端点配置。
         *
         * @return 端点配置
         */
        public Endpoint getOllama() {
            return ollama;
        }
    }

    /**
     * 单个 provider 的端点配置；key 允许为空（Ollama 本地调用不需要 key）。
     */
    public static class Endpoint {

        private String baseUrl = "";

        private String apiKey = "";

        private String model = "";

        /**
         * 读取基础地址。
         *
         * @return 基础地址
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * 设置基础地址。
         *
         * @param baseUrl 基础地址
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * 读取密钥。
         *
         * @return 密钥
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * 设置密钥。
         *
         * @param apiKey 密钥
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * 读取模型名。
         *
         * @return 模型名
         */
        public String getModel() {
            return model;
        }

        /**
         * 设置模型名。
         *
         * @param model 模型名
         */
        public void setModel(String model) {
            this.model = model;
        }
    }

    /**
     * Prompt 版本管理配置。
     */
    public static class Prompt {

        /**
         * 默认 Prompt 键。
         */
        private String defaultKey = "agent.default";

        /**
         * 首次使用时写入版本 1 的模板内容；落库后即可通过版本接口滚动，
         * 不再以常量为唯一事实来源。
         */
        private String defaultContent = """
                你是 APS 排产助手。请基于以下上下文给出结果。
                上下文：
                {{context}}
                只输出一个 JSON 对象，不要输出解释性文字。""";

        /**
         * 读取默认 Prompt 键。
         *
         * @return 默认键
         */
        public String getDefaultKey() {
            return defaultKey;
        }

        /**
         * 设置默认 Prompt 键。
         *
         * @param defaultKey 默认键
         */
        public void setDefaultKey(String defaultKey) {
            this.defaultKey = defaultKey;
        }

        /**
         * 读取默认模板内容。
         *
         * @return 模板内容
         */
        public String getDefaultContent() {
            return defaultContent;
        }

        /**
         * 设置默认模板内容。
         *
         * @param defaultContent 模板内容
         */
        public void setDefaultContent(String defaultContent) {
            this.defaultContent = defaultContent;
        }
    }

    /**
     * 检查点配置。
     */
    public static class Checkpoint {

        /**
         * 是否在每个步骤后落检查点；关闭后编排仍可运行，但无法断点续跑。
         */
        private boolean enabled = true;

        /**
         * 读取检查点开关。
         *
         * @return true 表示每步落库
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置检查点开关。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
