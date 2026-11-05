package com.hengce.aps.web.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M2 认证与限流配置（C27）。
 *
 * <p>所有密钥只从配置/环境变量读取，禁止入库、入日志。源码内<b>不提供任何默认密钥</b>：
 * JWT 密钥与 dev API Key 都必须显式配置，缺失时由 {@code ApiSecurityConfigurationValidator}
 * 在启动阶段 fail-fast。认证模式缺省为 {@code prod}，{@code off} 只能显式配置。</p>
 */
@Component
@ConfigurationProperties(prefix = "aps.security")
public class ApiSecurityProperties {

    private static final String DEFAULT_REQUIRED_ROLE = "APS_USER";

    /**
     * 认证模式：{@code prod/dev/off}；为空时按 {@code prod} 处理（不再回落 {@code off}）。
     */
    private String mode;

    /**
     * 开发模式 API Key。
     */
    private ApiKeys apiKeys = new ApiKeys();

    /**
     * JWT 配置。
     */
    private Jwt jwt = new Jwt();

    /**
     * 限流配置。
     */
    private RateLimit rateLimit = new RateLimit();

    /**
     * WebSocket 配置。
     */
    private WebSocket websocket = new WebSocket();

    /**
     * dev 专用旁路开关（默认全关，prod 下无条件不生效）。
     */
    private Dev dev = new Dev();

    public Dev getDev() {
        return dev;
    }

    public void setDev(Dev dev) {
        this.dev = dev == null ? new Dev() : dev;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * 是否显式配置了认证模式。
     *
     * @return true 表示配置了非空白值
     */
    public boolean isModeExplicit() {
        return mode != null && !mode.isBlank();
    }

    /**
     * 生效认证模式：未配置时返回 {@code prod}，绝不再回落 {@code off}。
     *
     * @return {@code prod} / {@code dev} / {@code off}
     */
    public String resolvedMode() {
        return isModeExplicit() ? mode.trim().toLowerCase() : "prod";
    }

    public WebSocket getWebsocket() {
        return websocket;
    }

    public void setWebsocket(WebSocket websocket) {
        this.websocket = websocket;
    }

    public ApiKeys getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(ApiKeys apiKeys) {
        this.apiKeys = apiKeys;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    /**
     * dev 专用旁路开关（P1-3）。
     *
     * <p>默认关闭；只有 active profile 为 {@code dev}（且非 {@code prod}）时才会被读取。
     * prod 下即使把开关置 true 也不生效。</p>
     */
    public static class Dev {

        /**
         * 是否允许 {@code X-API-Key}；开启后写密钥等价 ADMIN，每次使用打 WARN。
         */
        private boolean apiKeyEnabled;

        /**
         * 是否允许 {@code X-User-Id} 覆盖审计操作人；开启后审计行标记来源为请求头。
         */
        private boolean userIdHeaderEnabled;

        public boolean isApiKeyEnabled() {
            return apiKeyEnabled;
        }

        public void setApiKeyEnabled(boolean apiKeyEnabled) {
            this.apiKeyEnabled = apiKeyEnabled;
        }

        public boolean isUserIdHeaderEnabled() {
            return userIdHeaderEnabled;
        }

        public void setUserIdHeaderEnabled(boolean userIdHeaderEnabled) {
            this.userIdHeaderEnabled = userIdHeaderEnabled;
        }
    }

    /**
     * API Key 配置。
     */
    public static class ApiKeys {

        /**
         * 写密钥：允许写端点；未配置时视为不可用。
         */
        private String write;

        /**
         * 读密钥：仅读端点，写端点返回 403；未配置时视为不可用。
         */
        private String read;

        public String getWrite() {
            return write;
        }

        public void setWrite(String write) {
            this.write = write;
        }

        public String getRead() {
            return read;
        }

        public void setRead(String read) {
            this.read = read;
        }
    }

    /**
     * JWT 配置（HS256）。
     */
    public static class Jwt {

        /**
         * HS256 共享密钥，至少 32 字节；无默认值，未配置即启动失败。
         */
        private String secret;

        /**
         * 必须包含的角色。
         */
        private String requiredRole = DEFAULT_REQUIRED_ROLE;

        /**
         * 访问令牌有效期（秒），C30 固定 28800（8 小时，无 refresh）。
         */
        private long ttlSeconds = 28800L;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getRequiredRole() {
            return requiredRole;
        }

        public void setRequiredRole(String requiredRole) {
            this.requiredRole = requiredRole == null || requiredRole.isBlank()
                    ? DEFAULT_REQUIRED_ROLE : requiredRole;
        }
    }

    /**
     * WebSocket 配置（P1-4）。
     */
    public static class WebSocket {

        /**
         * 允许的 Origin 白名单（支持 {@code *} 通配模式）；默认仅本地前端开发端口。
         */
        private List<String> allowedOrigins = List.of("http://localhost:5173", "http://127.0.0.1:5173");

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
        }
    }

    /**
     * 限流配置（单实例固定窗口）。
     */
    public static class RateLimit {

        /**
         * 是否启用。
         */
        private boolean enabled = true;

        /**
         * 每个窗口允许的请求数。
         */
        private int capacity = 100;

        /**
         * 窗口长度（秒）。
         */
        private int windowSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }
}
