package com.hengce.aps.service.rbac;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengce.aps.infrastructure.persistence.mapper.rbac.UserMapper;
import com.hengce.aps.infrastructure.persistence.rbac.UserEntity;
import com.hengce.aps.service.audit.AuditEventType;
import com.hengce.aps.service.audit.AuditLogService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * admin 首次登录口令初始化（V1 种子 {@code password_hash} 为空时的 dev 兜底）。
 *
 * <p>三个条件必须同时满足才写入：激活 {@code dev} profile、环境变量
 * {@code APS_ADMIN_INIT_PASSWORD} 非空、目标 {@code users.username='admin'} 的
 * {@code password_hash IS NULL}。写入使用条件 UPDATE，已有口令永不覆盖；任一条件不满足
 * 时静默 no-op，仅打一行说明原因的 INFO 日志，日志与审计均不含口令明文。</p>
 */
@Component
public class AdminPasswordBootstrap {

    /**
     * 初始口令环境变量名。
     */
    public static final String PASSWORD_ENV_KEY = "APS_ADMIN_INIT_PASSWORD";

    /**
     * 允许执行初始化的 profile。
     */
    public static final String DEV_PROFILE = "dev";

    /**
     * 种子管理员登录名。
     */
    public static final String ADMIN_USERNAME = "admin";

    private static final Logger LOG = LoggerFactory.getLogger(AdminPasswordBootstrap.class);

    private final UserMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    private final AuditLogService auditLogService;

    private final Environment environment;

    private final Clock clock;

    /**
     * 构造初始化组件。
     *
     * @param userMapper 用户 Mapper
     * @param passwordEncoder bcrypt 编码器
     * @param auditLogService 审计服务
     * @param environment 运行环境（读取 profile 与环境变量）
     * @param clock 时钟
     */
    public AdminPasswordBootstrap(UserMapper userMapper, PasswordEncoder passwordEncoder,
                                  AuditLogService auditLogService, Environment environment, Clock clock) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.environment = environment;
        this.clock = clock;
    }

    /**
     * 按门控执行初始化；任何不满足条件的分支都只记一条 INFO 并返回，不抛异常。
     *
     * @return 执行结果
     */
    public Outcome bootstrap() {
        if (!environment.acceptsProfiles(Profiles.of(DEV_PROFILE))) {
            LOG.info("跳过 admin 初始口令：当前未激活 dev profile，生产环境不执行口令初始化");
            return Outcome.SKIPPED_NOT_DEV;
        }
        String initialPassword = environment.getProperty(PASSWORD_ENV_KEY);
        if (initialPassword == null || initialPassword.isBlank()) {
            LOG.info("跳过 admin 初始口令：未设置环境变量 {}", PASSWORD_ENV_KEY);
            return Outcome.SKIPPED_NO_PASSWORD;
        }

        UserEntity admin = userMapper.selectOne(Wrappers.lambdaQuery(UserEntity.class)
                .eq(UserEntity::getUsername, ADMIN_USERNAME));
        if (admin == null) {
            LOG.info("跳过 admin 初始口令：未找到用户 {}", ADMIN_USERNAME);
            return Outcome.SKIPPED_USER_NOT_FOUND;
        }
        if (admin.getPasswordHash() != null && !admin.getPasswordHash().isBlank()) {
            LOG.info("跳过 admin 初始口令：用户 {} 已存在口令，保留原口令不覆盖", ADMIN_USERNAME);
            return Outcome.ALREADY_SET;
        }

        String passwordHash = passwordEncoder.encode(initialPassword);
        int updated = userMapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                .set(UserEntity::getPasswordHash, passwordHash)
                .set(UserEntity::getUpdatedAt, OffsetDateTime.now(clock))
                .eq(UserEntity::getUsername, ADMIN_USERNAME)
                .isNull(UserEntity::getPasswordHash));
        if (updated == 0) {
            LOG.info("跳过 admin 初始口令：并发初始化已写入，保留先写入的口令");
            return Outcome.ALREADY_SET;
        }

        recordAudit(admin);
        LOG.info("admin 初始口令已由 dev 初始化流程写入，后续启动不会再覆盖");
        return Outcome.WRITTEN;
    }

    private void recordAudit(UserEntity admin) {
        try {
            auditLogService.recordForUser((UUID) admin.getId(), AuditEventType.USER_UPDATED,
                    ADMIN_USERNAME, null, Map.of("passwordInitialized", true));
        } catch (RuntimeException exception) {
            LOG.warn("admin 初始口令已写入，但审计落库失败：{}", exception.getClass().getSimpleName());
        }
    }

    /**
     * 初始化结果。
     */
    public enum Outcome {

        /** 已写入新口令。 */
        WRITTEN,

        /** 已有口令（含并发先写入），未覆盖。 */
        ALREADY_SET,

        /** 非 dev profile，跳过。 */
        SKIPPED_NOT_DEV,

        /** 未提供初始口令环境变量，跳过。 */
        SKIPPED_NO_PASSWORD,

        /** 未找到 admin 用户，跳过。 */
        SKIPPED_USER_NOT_FOUND
    }
}
