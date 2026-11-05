package com.hengce.aps.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import com.hengce.aps.web.config.ApiSecurityProperties;
import com.hengce.aps.web.security.ApsSecurityContext;
import com.hengce.aps.web.security.AuthMechanism;
import com.hengce.aps.web.security.AuthPrincipal;
import com.hengce.aps.web.security.JwtClaims;
import com.hengce.aps.web.security.JwtTokenService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 认证过滤器（C27 + C30）。
 *
 * <p>按配置分级：{@code prod} 全端点强制 JWT（HS256，payload 必须含 {@code APS_USER}）；
 * {@code dev} 写端点强制认证，支持 {@code X-API-Key}（写密钥放行、读密钥 403）与 Bearer，
 * 认证通过后 API Key 等价 ADMIN；{@code off} 全放行，<b>只能显式配置</b>。
 * 未显式配置 {@code aps.security.mode} 时按 {@code prod} 处理，不再回落 {@code off}。</p>
 *
 * <p><strong>审计身份</strong>：prod 取 JWT {@code sub} 写入 {@link AuditContext}，dev Bearer 保留
 * {@code X-User-Id} 注入（缺省回落到 {@code sub}），dev API Key 不伪造用户（操作人为 NULL），
 * 但每次使用都会落 INFO 日志。请求结束 finally 清理 ThreadLocal。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiSecurityFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiSecurityFilter.class);

    private static final String API_PREFIX = ApsConstants.API_PREFIX;

    private static final String HEALTH_PATH = API_PREFIX + "/health";

    private static final String LOGIN_PATH = API_PREFIX + "/auth/login";

    private static final String API_KEY_HEADER = "X-API-Key";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String USER_ID_HEADER = ApsConstants.HEADER_USER_ID;

    private static final String DEV_USER_ID_PROPERTY = "aps.audit.dev-user-id";

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ApiSecurityProperties properties;

    private final ObjectMapper objectMapper;

    private final JwtTokenService jwtTokenService;

    private final org.springframework.core.env.Environment environment;

    /**
     * 构造过滤器。
     *
     * @param properties 安全配置
     * @param objectMapper JSON 序列化器
     * @param jwtTokenService JWT 服务
     * @param environment Spring 环境
     */
    public ApiSecurityFilter(ApiSecurityProperties properties, ObjectMapper objectMapper,
                             JwtTokenService jwtTokenService,
                             org.springframework.core.env.Environment environment) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jwtTokenService = jwtTokenService;
        this.environment = environment;
    }

    /**
     * 启动时打印生效模式与来源（不打印密钥本身）。
     */
    @PostConstruct
    public void logEffectiveMode() {
        LOGGER.info("API 安全模式生效: mode={}，source={}，activeProfiles={}",
                properties.resolvedMode(),
                properties.isModeExplicit() ? "aps.security.mode" : "default(prod)",
                Arrays.toString(environment.getActiveProfiles()));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(API_PREFIX) || path.equals(HEALTH_PATH) || path.equals(LOGIN_PATH)
                || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String mode = resolveMode();
        AuditContext.clear();
        ApsSecurityContext.clear();
        try {
            ApsSecurityContext.setMode(mode);
            if ("off".equals(mode)) {
                filterChain.doFilter(request, response);
                return;
            }
            boolean mutating = WRITE_METHODS.contains(request.getMethod());
            if ("prod".equals(mode)) {
                JwtClaims claims = authenticateJwt(request, response);
                if (claims == null) {
                    return;
                }
                applyJwt(claims);
            } else {
                AuthOutcome outcome = authenticateDev(request, response, mutating);
                if (outcome == AuthOutcome.REJECTED) {
                    // 无凭证或凭证被忽略时 authenticateDev 不会写响应；此处统一落 401，避免静默 200 空包
                    if (response.getStatus() < 400) {
                        writeError(response, ErrorCode.UNAUTHORIZED, null);
                    }
                    return;
                }
                if (outcome == AuthOutcome.UNKNOWN && mutating) {
                    writeError(response, ErrorCode.UNAUTHORIZED, null);
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            AuditContext.clear();
            TenantContext.clear();
            ApsSecurityContext.clear();
        }
    }

    private void applyJwt(JwtClaims claims) {
        ApsSecurityContext.setPrincipal(new AuthPrincipal(claims.userId(), claims.roles(), false,
                AuthMechanism.JWT));
        AuditContext.setCurrentUserId(claims.userId());
        AuditContext.setIdentitySource(AuditContext.IdentitySource.JWT);
        TenantContext.setTenantId(claims.tenantId());
    }

    /**
     * dev 旁路是否可用：必须激活 dev、非 prod profile，且配置显式开启。
     *
     * @param enabled 配置开关
     * @return true 表示允许使用
     */
    private boolean devBypassAllowed(boolean enabled) {
        return enabled && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"))
                && !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
    }

    /**
     * dev 模式认证：凭证存在则必须有效；无凭证时仅读端点允许匿名。
     *
     * @param request 请求
     * @param response 响应
     * @param mutating 是否写方法
     * @return 认证结果
     * @throws IOException 写响应失败
     */
    private AuthOutcome authenticateDev(HttpServletRequest request, HttpServletResponse response, boolean mutating)
            throws IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            if (!devBypassAllowed(properties.getDev().isApiKeyEnabled())) {
                LOGGER.warn("dev API Key 未启用或非 dev profile，已忽略该请求头: path={}, method={}",
                        request.getRequestURI(), request.getMethod());
            } else {
                if (apiKey.equals(properties.getApiKeys().getWrite())) {
                    ApsSecurityContext.setPrincipal(new AuthPrincipal(null, List.of(), true, AuthMechanism.API_KEY));
                    LOGGER.warn("dev API Key(write) 以 ADMIN 等价权限使用（仅 dev 显式开启）: path={}, method={}",
                            request.getRequestURI(), request.getMethod());
                    return AuthOutcome.OK;
                }
                if (apiKey.equals(properties.getApiKeys().getRead())) {
                    if (mutating) {
                        writeError(response, ErrorCode.FORBIDDEN, "读密钥不能访问写端点");
                        return AuthOutcome.REJECTED;
                    }
                    ApsSecurityContext.setPrincipal(new AuthPrincipal(null, List.of(), true, AuthMechanism.API_KEY));
                    LOGGER.warn("dev API Key(read) 以 ADMIN 等价权限使用（仅 dev 显式开启）: path={}, method={}",
                            request.getRequestURI(), request.getMethod());
                    return AuthOutcome.OK;
                }
                writeError(response, ErrorCode.UNAUTHORIZED, null);
                return AuthOutcome.REJECTED;
            }
        }
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            JwtClaims claims = authenticateJwt(request, response);
            if (claims == null) {
                return AuthOutcome.REJECTED;
            }
            applyJwt(claims);
            if (!applyDevAuditUser(request, response)) {
                return AuthOutcome.REJECTED;
            }
            if (AuditContext.getCurrentUserId() == null) {
                AuditContext.setCurrentUserId(claims.userId());
            }
            return AuthOutcome.OK;
        }
        return mutating ? AuthOutcome.REJECTED : AuthOutcome.UNKNOWN;
    }

    /**
     * dev 模式解析操作人：优先 {@code X-User-Id}（非法 UUID → 400），否则用 {@code aps.audit.dev-user-id}。
     *
     * @param request 请求
     * @param response 响应
     * @return 是否继续处理
     * @throws IOException 写错误响应失败
     */
    private boolean applyDevAuditUser(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String header = request.getHeader(USER_ID_HEADER);
        if (header != null && !header.isBlank()) {
            if (!devBypassAllowed(properties.getDev().isUserIdHeaderEnabled())) {
                LOGGER.warn("X-User-Id 未启用（需 dev profile 且显式开启），已忽略该请求头");
            } else {
                UUID userId = parseUuid(header);
                if (userId == null) {
                    writeError(response, ErrorCode.BAD_REQUEST, "X-User-Id 必须为合法 UUID");
                    return false;
                }
                AuditContext.setCurrentUserId(userId);
                AuditContext.setIdentitySource(AuditContext.IdentitySource.DEV_HEADER);
                LOGGER.warn("审计操作人由请求头 X-User-Id 指定（dev 显式开启，非 JWT 用户）: userId={}", userId);
                return true;
            }
        }
        String configured = environment.getProperty(DEV_USER_ID_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            UUID userId = parseUuid(configured);
            if (userId == null) {
                LOGGER.warn("aps.audit.dev-user-id 不是合法 UUID，已忽略: {}", configured);
            } else {
                AuditContext.setCurrentUserId(userId);
                AuditContext.setIdentitySource(AuditContext.IdentitySource.CONFIGURED);
            }
        }
        return true;
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String resolveMode() {
        // 缺省 prod：安全模式默认开启；off 只能通过 aps.security.mode 显式配置（P0-1）
        return properties.resolvedMode();
    }

    private JwtClaims authenticateJwt(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            writeError(response, ErrorCode.UNAUTHORIZED, null);
            return null;
        }
        JwtClaims claims = jwtTokenService.verify(authorization.substring(BEARER_PREFIX.length()).trim());
        if (claims == null) {
            writeError(response, ErrorCode.UNAUTHORIZED, null);
            return null;
        }
        String required = properties.getJwt().getRequiredRole();
        if (!claims.roles().contains(required)) {
            writeError(response, ErrorCode.FORBIDDEN, "令牌缺少必需角色");
            return null;
        }
        return claims;
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode, String message) throws IOException {
        Result<Void> body = message == null
                ? Result.failure(errorCode)
                : Result.failure(errorCode, message);
        response.setStatus(errorCode == ErrorCode.FORBIDDEN
                ? HttpServletResponse.SC_FORBIDDEN
                : errorCode == ErrorCode.BAD_REQUEST
                ? HttpServletResponse.SC_BAD_REQUEST : HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /**
     * dev 认证结果。
     */
    private enum AuthOutcome {
        /** 已认证（API Key 或 JWT）。 */
        OK,
        /** 凭证无效/写端点缺凭证/读密钥越权。 */
        REJECTED,
        /** 读端点无凭证，按匿名放行。 */
        UNKNOWN
    }
}
