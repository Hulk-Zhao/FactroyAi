package com.hengce.aps.service.rbac;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.mapper.rbac.RoleMapper;
import com.hengce.aps.infrastructure.persistence.rbac.RbacRepository;
import com.hengce.aps.infrastructure.persistence.rbac.RoleEntity;
import com.hengce.aps.service.audit.AuditEventType;
import com.hengce.aps.service.audit.AuditLogService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色与授权服务（C30）。
 */
@Service
public class RoleService {

    /**
     * 角色码格式：大写字母开头，允许大写字母/数字/下划线，长度 2-32。
     */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");

    private final RoleMapper roleMapper;

    private final RbacRepository rbacRepository;

    private final AuditLogService auditLogService;

    private final PermissionService permissionService;

    /**
     * 构造服务。
     *
     * @param roleMapper 角色 Mapper
     * @param rbacRepository RBAC 持久层
     * @param auditLogService 审计服务
     * @param permissionService 权限服务
     */
    public RoleService(RoleMapper roleMapper, RbacRepository rbacRepository, AuditLogService auditLogService,
                       PermissionService permissionService) {
        this.roleMapper = roleMapper;
        this.rbacRepository = rbacRepository;
        this.auditLogService = auditLogService;
        this.permissionService = permissionService;
    }

    /**
     * 列出角色及其权限。
     *
     * @return 角色列表
     */
    public List<RoleView> list() {
        List<RoleEntity> roles = roleMapper.selectList(Wrappers.lambdaQuery(RoleEntity.class)
                .orderByAsc(RoleEntity::getCode));
        List<UUID> roleIds = roles.stream().map(entity -> asUuid(entity.getId())).toList();
        Map<UUID, List<String>> permissions = rbacRepository.findPermissionCodesByRoleIds(
                TenantContext.getTenantId(), roleIds);
        return roles.stream()
                .map(entity -> toView(entity, permissions.getOrDefault(asUuid(entity.getId()), List.of())))
                .toList();
    }

    /**
     * 创建自定义角色。
     *
     * @param command 创建命令
     * @return 新角色
     */
    @Transactional
    public RoleView create(RoleCreateCommand command) {
        if (command.code() == null || !CODE_PATTERN.matcher(command.code()).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色码格式不合法");
        }
        if (roleMapper.selectCount(Wrappers.lambdaQuery(RoleEntity.class)
                .eq(RoleEntity::getCode, command.code())) > 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "角色码已存在");
        }
        List<String> permissionCodes = normalizePermissions(command.permissionCodes());
        List<UUID> permissionIds = resolvePermissionIds(permissionCodes);

        RoleEntity entity = new RoleEntity();
        UUID id = UUID.randomUUID();
        entity.setId(id);
        entity.setCode(command.code());
        entity.setName(command.name());
        entity.setDescription(command.description());
        entity.setBuiltin(false);
        try {
            roleMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "角色码已存在");
        }
        rbacRepository.replaceRolePermissions(TenantContext.getTenantId(), id, permissionIds);
        permissionService.invalidateAll();
        auditLogService.record(AuditEventType.ROLE_CREATED, id.toString(), null,
                Map.of("code", command.code(), "permissionCodes", permissionCodes));
        return toView(entity, permissionCodes);
    }

    /**
     * 全量替换角色权限。
     *
     * @param roleId 角色主键
     * @param permissionCodes 目标权限码
     * @return 更新后的角色
     */
    @Transactional
    public RoleView replacePermissions(UUID roleId, List<String> permissionCodes) {
        RoleEntity role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        List<String> targetCodes = normalizePermissions(permissionCodes);
        List<UUID> permissionIds = resolvePermissionIds(targetCodes);
        List<String> before = rbacRepository.findPermissionCodesByRoleId(TenantContext.getTenantId(), roleId);
        rbacRepository.replaceRolePermissions(TenantContext.getTenantId(), roleId, permissionIds);
        permissionService.invalidateAll();
        auditLogService.record(AuditEventType.ROLE_PERMISSION_CHANGED, role.getCode(),
                Map.of("permissionCodes", before), Map.of("permissionCodes", targetCodes));
        return toView(role, targetCodes);
    }

    private List<UUID> resolvePermissionIds(List<String> permissionCodes) {
        if (permissionCodes.isEmpty()) {
            return List.of();
        }
        for (String code : permissionCodes) {
            if (!PermissionCode.isValid(code)) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "权限码不在闭集内: " + code);
            }
        }
        List<UUID> permissionIds = rbacRepository.findPermissionIdsByCodes(TenantContext.getTenantId(),
                permissionCodes);
        if (permissionIds.size() != permissionCodes.size()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "权限码不存在或不属于当前租户");
        }
        return permissionIds;
    }

    private static List<String> normalizePermissions(List<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(permissionCodes.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .toList()));
    }

    private static RoleView toView(RoleEntity entity, List<String> permissionCodes) {
        return new RoleView(asUuid(entity.getId()), entity.getCode(), entity.getName(),
                entity.getDescription(), permissionCodes, entity.getVersion());
    }

    private static UUID asUuid(Object value) {
        return value instanceof UUID uuid ? uuid : null;
    }
}
