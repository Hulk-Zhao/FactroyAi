package com.hengce.aps.service.rbac;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.mapper.rbac.UserMapper;
import com.hengce.aps.infrastructure.persistence.rbac.RbacRepository;
import com.hengce.aps.infrastructure.persistence.rbac.UserEntity;
import com.hengce.aps.service.audit.AuditEventType;
import com.hengce.aps.service.audit.AuditLogService;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录认证服务（C30 / 25.1.2）。
 *
 * <p>失败计数落 {@code users.ext_attrs}（不新增列）；连续 5 次失败置 {@code status=LOCKED}
 * 与 {@code locked_until=now+15min}。登录审计成功/失败均落库，失败使用独立事务。</p>
 */
@Service
public class AuthService {

    /**
     * 触发锁定的连续失败次数。
     */
    public static final int MAX_FAILURES = 5;

    /**
     * 锁定时长。
     */
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    /**
     * ext_attrs 中的失败计数字段。
     */
    private static final String FAILURE_COUNT_KEY = "loginFailureCount";

    /**
     * 统一错误文案，避免账号枚举。
     */
    private static final String CREDENTIAL_ERROR = "用户名或密码错误";

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    private final UserMapper userMapper;

    private final RbacRepository rbacRepository;

    private final AuditLogService auditLogService;

    private final PasswordEncoder passwordEncoder;

    private final Clock clock;

    private final Environment environment;

    /**
     * 构造服务。
     *
     * @param userMapper 用户 Mapper
     * @param rbacRepository RBAC 持久层
     * @param auditLogService 审计服务
     * @param passwordEncoder bcrypt 编码器
     * @param clock 时钟
     * @param environment Spring 环境（判断 prod 以决定租户缺失时的处理）
     */
    public AuthService(UserMapper userMapper, RbacRepository rbacRepository,
                       AuditLogService auditLogService, PasswordEncoder passwordEncoder, Clock clock,
                       Environment environment) {
        this.userMapper = userMapper;
        this.rbacRepository = rbacRepository;
        this.auditLogService = auditLogService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.environment = environment;
    }

    /**
     * 登录校验。
     *
     * @param username 登录名
     * @param password 明文口令
     * @return 认证主体
     * @throws BizException 凭证错误、账号停用或锁定中（40100）
     */
    public AuthenticatedUser login(String username, String password) {
        UUID tenantId = resolveTenant(username);
        TenantContext.setTenantId(tenantId);
        try {
            return authenticate(username, password, tenantId);
        } finally {
            TenantContext.clear();
        }
    }

    private AuthenticatedUser authenticate(String username, String password, UUID tenantId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        UserEntity user = findUser(username);
        if (user == null) {
            auditLoginFailed(tenantId, null, username);
            throw unauthorized();
        }

        if (UserStatus.DISABLED.name().equals(user.getStatus())) {
            auditLoginFailed(tenantId, uuid(user.getId()), username);
            throw unauthorized();
        }

        if (UserStatus.LOCKED.name().equals(user.getStatus())) {
            if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
                auditLoginFailed(tenantId, uuid(user.getId()), username);
                throw unauthorized();
            }
            unlock(user);
        }

        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            registerFailure(user, now);
            auditLoginFailed(tenantId, uuid(user.getId()), username);
            throw unauthorized();
        }

        user.setStatus(UserStatus.ACTIVE.name());
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        clearFailures(user);
        userMapper.updateById(user);

        List<String> roleCodes = rbacRepository.findRoleCodesByUserId(tenantId, uuid(user.getId()));
        auditLogService.recordForUser(uuid(user.getId()), AuditEventType.AUTH_LOGIN, username, null, null);
        return new AuthenticatedUser(uuid(user.getId()), tenantId, roleCodes);
    }

    /**
     * 解析登录用户真实租户：以 {@code users.tenant_id} 为准，而不是线程默认租户。
     *
     * <p>同名账号跨多个租户、或账号无租户时明确拒绝（prod）；dev 下“账号无租户”才回退默认
     * 租户并打 WARN，回退必须显式可见。账号不存在不在此处区分，统一由后续 401 处理。</p>
     *
     * @param username 登录名
     * @return 生效租户
     */
    private UUID resolveTenant(String username) {
        List<RbacRepository.UsernameTenant> matches = rbacRepository.findUsersByUsername(username);
        Set<UUID> tenants = new LinkedHashSet<>();
        for (RbacRepository.UsernameTenant match : matches) {
            if (match.tenantId() != null) {
                tenants.add(match.tenantId());
            }
        }
        if (tenants.size() > 1) {
            LOG.warn("登录账号跨租户冲突，拒绝登录: username={}, tenantCount={}", username, tenants.size());
            throw unauthorized();
        }
        if (matches.isEmpty()) {
            return TenantContext.getTenantId();
        }
        if (tenants.isEmpty()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                LOG.warn("登录账号缺少租户，prod 拒绝登录: username={}", username);
                throw unauthorized();
            }
            LOG.warn("登录账号缺少租户，dev 显式回退默认租户: username={}", username);
            return TenantContext.DEFAULT_TENANT_ID;
        }
        UUID resolved = tenants.iterator().next();
        if (TenantContext.hasExplicitTenant() && !resolved.equals(TenantContext.getTenantId())) {
            LOG.warn("登录租户与请求指定租户不一致，拒绝登录: username={}", username);
            throw unauthorized();
        }
        return resolved;
    }

    private UserEntity findUser(String username) {
        return userMapper.selectOne(Wrappers.lambdaQuery(UserEntity.class)
                .eq(UserEntity::getUsername, username));
    }

    private void registerFailure(UserEntity user, OffsetDateTime now) {
        int failures = failureCount(user) + 1;
        user.getExtAttrs().put(FAILURE_COUNT_KEY, failures);
        if (failures >= MAX_FAILURES) {
            user.setStatus(UserStatus.LOCKED.name());
            user.setLockedUntil(now.plus(LOCK_DURATION));
        }
        userMapper.updateById(user);
    }

    private static int failureCount(UserEntity user) {
        Object value = user.getExtAttrs().get(FAILURE_COUNT_KEY);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static void clearFailures(UserEntity user) {
        user.getExtAttrs().remove(FAILURE_COUNT_KEY);
    }

    private void unlock(UserEntity user) {
        user.setStatus(UserStatus.ACTIVE.name());
        user.setLockedUntil(null);
        clearFailures(user);
    }

    private void auditLoginFailed(UUID tenantId, UUID userId, String username) {
        auditLogService.recordLoginFailed(tenantId, userId, username);
    }

    private static BizException unauthorized() {
        return new BizException(ErrorCode.UNAUTHORIZED, CREDENTIAL_ERROR);
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return null;
    }
}
