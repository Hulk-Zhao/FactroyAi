package com.hengce.aps.service.rbac;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import com.hengce.aps.infrastructure.persistence.mapper.rbac.UserMapper;
import com.hengce.aps.infrastructure.persistence.rbac.RbacRepository;
import com.hengce.aps.infrastructure.persistence.rbac.UserEntity;
import com.hengce.aps.service.audit.AuditEventType;
import com.hengce.aps.service.audit.AuditLogService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户管理服务（C30）。
 */
@Service
public class UserService {

    private final UserMapper userMapper;

    private final RbacRepository rbacRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuditLogService auditLogService;

    private final PermissionService permissionService;

    /**
     * 构造服务。
     *
     * @param userMapper 用户 Mapper
     * @param rbacRepository RBAC 持久层
     * @param passwordEncoder bcrypt 编码器
     * @param auditLogService 审计服务
     * @param permissionService 权限服务
     */
    public UserService(UserMapper userMapper, RbacRepository rbacRepository, PasswordEncoder passwordEncoder,
                       AuditLogService auditLogService, PermissionService permissionService) {
        this.userMapper = userMapper;
        this.rbacRepository = rbacRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.permissionService = permissionService;
    }

    /**
     * 分页查询用户。
     *
     * @param query 分页参数
     * @param status 可选状态过滤
     * @return 分页结果
     */
    public PageResult<UserView> list(PageQuery query, UserStatus status) {
        PageQuery normalized = query == null ? new PageQuery() : query.normalize();
        Page<UserEntity> page = userMapper.selectPage(new Page<>(normalized.getPage(), normalized.getSize()),
                Wrappers.lambdaQuery(UserEntity.class)
                        .eq(status != null, UserEntity::getStatus, status == null ? null : status.name())
                        .orderByAsc(UserEntity::getUsername));
        List<UserEntity> records = page.getRecords();
        Map<UUID, List<String>> roles = rbacRepository.findRoleCodesByUserIds(TenantContext.getTenantId(),
                records.stream().map(entity -> asUuid(entity.getId())).toList());
        List<UserView> items = records.stream()
                .map(entity -> toView(entity, roles.getOrDefault(asUuid(entity.getId()), List.of())))
                .toList();
        return PageResult.of(items, normalized, page.getTotal());
    }

    /**
     * 创建用户。
     *
     * @param command 创建命令
     * @return 新用户
     */
    @Transactional
    public UserView create(UserCreateCommand command) {
        if (userMapper.selectCount(Wrappers.lambdaQuery(UserEntity.class)
                .eq(UserEntity::getUsername, command.username())) > 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "登录名已存在");
        }
        List<String> roleCodes = normalizeRoleCodes(command.roleCodes());
        List<UUID> roleIds = resolveRoleIds(roleCodes);

        UserEntity entity = new UserEntity();
        UUID id = UUID.randomUUID();
        entity.setId(id);
        entity.setUsername(command.username());
        entity.setDisplayName(command.displayName());
        entity.setEmail(command.email());
        entity.setPhone(command.phone());
        entity.setStatus(UserStatus.ACTIVE.name());
        entity.setPasswordHash(passwordEncoder.encode(command.password()));
        try {
            userMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "登录名已存在");
        }
        rbacRepository.replaceUserRoles(TenantContext.getTenantId(), id, roleIds);
        auditLogService.record(AuditEventType.USER_CREATED, id.toString(), null,
                Map.of("username", command.username(), "roleCodes", roleCodes, "status", entity.getStatus()));
        return toView(entity, roleCodes);
    }

    /**
     * 修改用户（乐观锁 + 最后一个 ADMIN 保护）。
     *
     * @param userId 用户主键
     * @param command 修改命令
     * @return 修改后的用户
     */
    @Transactional
    public UserView update(UUID userId, UserUpdateCommand command) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (command.version() == null || !command.version().equals(entity.getVersion())) {
            throw new BizException(ErrorCode.STATE_CONFLICT);
        }
        UserStatus targetStatus = command.status();
        if (targetStatus != null && targetStatus != UserStatus.ACTIVE) {
            guardSelfDisable(userId);
            guardLastAdmin(userId);
        }

        Map<String, Object> before = snapshot(entity);
        List<UUID> roleIds = null;
        List<String> roleCodes = null;
        if (command.roleCodes() != null) {
            roleCodes = normalizeRoleCodes(command.roleCodes());
            roleIds = resolveRoleIds(roleCodes);
        }
        if (command.displayName() != null) {
            entity.setDisplayName(command.displayName());
        }
        if (command.email() != null) {
            entity.setEmail(command.email());
        }
        if (command.phone() != null) {
            entity.setPhone(command.phone());
        }
        if (targetStatus != null) {
            entity.setStatus(targetStatus.name());
            if (targetStatus != UserStatus.LOCKED) {
                entity.setLockedUntil(null);
            }
        }
        if (command.password() != null && !command.password().isBlank()) {
            entity.setPasswordHash(passwordEncoder.encode(command.password()));
        }
        int updated = userMapper.updateById(entity);
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT);
        }
        if (roleIds != null) {
            rbacRepository.replaceUserRoles(TenantContext.getTenantId(), userId, roleIds);
        }
        permissionService.invalidate(userId);
        auditLogService.record(AuditEventType.USER_UPDATED, userId.toString(), before, snapshot(entity));
        List<String> effectiveRoles = roleCodes != null ? roleCodes
                : rbacRepository.findRoleCodesByUserId(TenantContext.getTenantId(), userId);
        return toView(entity, effectiveRoles);
    }

    /**
     * 查询当前用户信息与权限（{@code GET /me}）。
     *
     * @param userId 用户主键
     * @return 用户 + 权限
     */
    public MeView me(UUID userId) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        List<String> roleCodes = rbacRepository.findRoleCodesByUserId(TenantContext.getTenantId(), userId);
        List<String> permissions = rbacRepository.findPermissionCodesByUserId(TenantContext.getTenantId(), userId);
        return new MeView(asUuid(entity.getId()), entity.getUsername(), entity.getDisplayName(), entity.getEmail(),
                entity.getPhone(), entity.getStatus(), entity.getLastLoginAt(), entity.getVersion(),
                roleCodes, permissions);
    }

    private void guardSelfDisable(UUID userId) {
        UUID operator = AuditContext.getCurrentUserId();
        if (operator != null && operator.equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "禁止停用当前操作人账号");
        }
    }

    private void guardLastAdmin(UUID userId) {
        UUID tenantId = TenantContext.getTenantId();
        if (rbacRepository.userHasRole(tenantId, userId, RoleCodes.ADMIN)
                && rbacRepository.countActiveAdminUsers(tenantId) <= 1) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "禁止停用最后一个 ADMIN 账号");
        }
    }

    private List<UUID> resolveRoleIds(List<String> roleCodes) {
        if (roleCodes.isEmpty()) {
            return List.of();
        }
        List<UUID> roleIds = rbacRepository.findRoleIdsByCodes(TenantContext.getTenantId(), roleCodes);
        if (roleIds.size() != roleCodes.size()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "角色码不存在或不属于当前租户");
        }
        return roleIds;
    }

    private static List<String> normalizeRoleCodes(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(roleCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .toList()));
    }

    private static UserView toView(UserEntity entity, List<String> roleCodes) {
        return new UserView(asUuid(entity.getId()), entity.getUsername(), entity.getDisplayName(),
                entity.getEmail(), entity.getPhone(), entity.getStatus(), entity.getLastLoginAt(),
                entity.getVersion(), roleCodes);
    }

    private static Map<String, Object> snapshot(UserEntity entity) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("username", entity.getUsername());
        value.put("displayName", entity.getDisplayName());
        value.put("email", entity.getEmail());
        value.put("phone", entity.getPhone());
        value.put("status", entity.getStatus());
        value.put("version", entity.getVersion());
        return value;
    }

    private static UUID asUuid(Object value) {
        return value instanceof UUID uuid ? uuid : null;
    }
}
