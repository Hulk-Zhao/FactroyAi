package com.hengce.aps.web.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 安全配置启动校验（P0-1）：密钥缺失/过短/使用历史公开值时直接 fail-fast，禁止静默回落。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>认证模式只接受 {@code prod/dev/off}，缺省为 {@code prod}；{@code off} 必须显式配置。</li>
 *   <li>JWT 密钥必须显式配置且不少于 32 字节，且禁止使用历史公开默认值。</li>
 *   <li>dev API Key 可选，但只能在 {@code dev} profile + {@code dev} 模式下使用，且禁止历史公开值。</li>
 * </ul>
 */
@Component
public class ApiSecurityConfigurationValidator implements InitializingBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiSecurityConfigurationValidator.class);

    /**
     * 历史公开默认密钥，出现在任何配置中都必须拒绝。
     */
    static final String LEGACY_JWT_SECRET = "hengce-aps-dev-secret-please-change-32bytes";

    /**
     * 历史公开 dev API Key。
     */
    static final String LEGACY_WRITE_KEY = "dev-write-key";

    static final String LEGACY_READ_KEY = "dev-read-key";

    private static final Set<String> SUPPORTED_MODES = Set.of("prod", "dev", "off");

    private static final int MIN_SECRET_BYTES = 32;

    private final ApiSecurityProperties properties;

    private final Environment environment;

    /**
     * 构造校验器。
     *
     * @param properties 安全配置
     * @param environment Spring 环境（读取 active profile）
     */
    public ApiSecurityConfigurationValidator(ApiSecurityProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String mode = properties.resolvedMode();
        String source = properties.isModeExplicit() ? "aps.security.mode" : "default(prod)";
        String[] profiles = environment.getActiveProfiles();
        LOGGER.info("安全配置校验：mode={} source={} activeProfiles={}", mode, source, Arrays.toString(profiles));

        if (!SUPPORTED_MODES.contains(mode)) {
            throw new IllegalStateException("aps.security.mode 配置非法: " + mode + "，仅支持 prod/dev/off");
        }

        boolean devProfile = Arrays.stream(profiles).anyMatch("dev"::equalsIgnoreCase);
        if ("dev".equals(mode) && !devProfile) {
            throw new IllegalStateException(
                    "aps.security.mode=dev 仅允许在 dev profile 下使用；请设置 spring.profiles.active=dev 后显式配置安全模式");
        }

        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "aps.security.jwt.secret 未配置：请显式设置 APS_SECURITY_JWT_SECRET（至少 "
                            + MIN_SECRET_BYTES + " 字节）；源码不提供默认密钥，未配置将拒绝启动");
        }
        if (LEGACY_JWT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "aps.security.jwt.secret 使用了历史公开默认密钥，该密钥已随源码泄露，禁止启用以防令牌伪造");
        }
        int secretBytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException("aps.security.jwt.secret 长度不足：当前 " + secretBytes
                    + " 字节，HS256 要求至少 " + MIN_SECRET_BYTES + " 字节");
        }

        String writeKey = properties.getApiKeys().getWrite();
        String readKey = properties.getApiKeys().getRead();
        boolean keysConfigured = isNotBlank(writeKey) || isNotBlank(readKey);
        if (LEGACY_WRITE_KEY.equals(writeKey) || LEGACY_READ_KEY.equals(readKey)) {
            throw new IllegalStateException(
                    "aps.security.api-keys 使用了历史公开默认密钥（dev-write-key/dev-read-key），该密钥已随源码泄露，禁止启用");
        }
        if (keysConfigured && !"dev".equals(mode)) {
            throw new IllegalStateException(
                    "aps.security.api-keys 仅在 aps.security.mode=dev 下受支持；当前 mode=" + mode);
        }
        if (keysConfigured && !devProfile) {
            throw new IllegalStateException(
                    "aps.security.api-keys 仅在 dev profile 下受支持；当前 activeProfiles=" + Arrays.toString(profiles));
        }

        LOGGER.info("安全配置校验通过：mode={} jwtSecret=configured({} bytes) devApiKeys={}",
                mode, secretBytes, keysConfigured ? "configured" : "absent");
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
