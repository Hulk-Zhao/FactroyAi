package com.hengce.aps.infrastructure.persistence.rbac;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * RBAC 关联表持久层（C30）。
 *
 * <p>{@code user_role} / {@code role_permission} 无 {@code tenant_id} 列（V1 DDL，租户拦截器已忽略），
 * 因此本类使用 {@link JdbcTemplate} 并通过 {@code role}-{@code permission} 的租户条件显式隔离。</p>
 */
@Repository
public class RbacRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public RbacRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询用户的业务角色码。
     *
     * @param tenantId 租户
     * @param userId 用户主键
     * @return 角色码列表
     */
    public List<String> findRoleCodesByUserId(UUID tenantId, UUID userId) {
        return jdbcTemplate.query("""
                SELECT r.code
                  FROM user_role ur
                  JOIN role r ON r.id = ur.role_id
                 WHERE ur.user_id = ? AND r.tenant_id = ? AND r.deleted = false
                 ORDER BY r.code
                """, (rs, rowNum) -> rs.getString(1), userId, tenantId);
    }

    /**
     * 批量查询用户的角色码。
     *
     * @param tenantId 租户
     * @param userIds 用户主键集合
     * @return userId → 角色码列表
     */
    public Map<UUID, List<String>> findRoleCodesByUserIds(UUID tenantId, Collection<UUID> userIds) {
        Map<UUID, List<String>> result = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        args.addAll(userIds);
        String placeholders = placeholders(userIds.size());
        jdbcTemplate.query("""
                SELECT ur.user_id, r.code
                  FROM user_role ur
                  JOIN role r ON r.id = ur.role_id
                 WHERE r.tenant_id = ? AND r.deleted = false AND ur.user_id IN (%s)
                 ORDER BY r.code
                """.formatted(placeholders),
                rs -> {
                    UUID userId = (UUID) rs.getObject(1);
                    result.computeIfAbsent(userId, key -> new java.util.ArrayList<>()).add(rs.getString(2));
                }, args.toArray());
        return result;
    }

    /**
     * 查询用户所有角色并集对应的权限码。
     *
     * @param tenantId 租户
     * @param userId 用户主键
     * @return 去重后的权限码
     */
    public List<String> findPermissionCodesByUserId(UUID tenantId, UUID userId) {
        return jdbcTemplate.query("""
                SELECT DISTINCT p.code
                  FROM user_role ur
                  JOIN role r ON r.id = ur.role_id
                  JOIN role_permission rp ON rp.role_id = r.id
                  JOIN permission p ON p.id = rp.permission_id
                 WHERE ur.user_id = ? AND r.tenant_id = ? AND r.deleted = false
                   AND p.tenant_id = ? AND p.deleted = false
                 ORDER BY p.code
                """, (rs, rowNum) -> rs.getString(1), userId, tenantId, tenantId);
    }

    /**
     * 查询角色已分配的权限码。
     *
     * @param tenantId 租户
     * @param roleId 角色主键
     * @return 权限码列表
     */
    public List<String> findPermissionCodesByRoleId(UUID tenantId, UUID roleId) {
        return jdbcTemplate.query("""
                SELECT p.code
                  FROM role_permission rp
                  JOIN permission p ON p.id = rp.permission_id
                 WHERE rp.role_id = ? AND p.tenant_id = ? AND p.deleted = false
                 ORDER BY p.code
                """, (rs, rowNum) -> rs.getString(1), roleId, tenantId);
    }

    /**
     * 批量查询角色已分配的权限码。
     *
     * @param tenantId 租户
     * @param roleIds 角色主键集合
     * @return roleId → 权限码列表
     */
    public Map<UUID, List<String>> findPermissionCodesByRoleIds(UUID tenantId, Collection<UUID> roleIds) {
        Map<UUID, List<String>> result = new HashMap<>();
        if (roleIds == null || roleIds.isEmpty()) {
            return result;
        }
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        args.addAll(roleIds);
        jdbcTemplate.query("""
                SELECT rp.role_id, p.code
                  FROM role_permission rp
                  JOIN permission p ON p.id = rp.permission_id
                 WHERE p.tenant_id = ? AND p.deleted = false AND rp.role_id IN (%s)
                 ORDER BY p.code
                """.formatted(placeholders(roleIds.size())),
                rs -> {
                    UUID roleId = (UUID) rs.getObject(1);
                    result.computeIfAbsent(roleId, key -> new java.util.ArrayList<>()).add(rs.getString(2));
                }, args.toArray());
        return result;
    }

    /**
     * 按角色码解析角色主键（仅本租户）。
     *
     * @param tenantId 租户
     * @param codes 角色码
     * @return 命中的角色主键
     */
    public List<UUID> findRoleIdsByCodes(UUID tenantId, Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        args.addAll(codes);
        return jdbcTemplate.query("""
                SELECT id FROM role
                 WHERE tenant_id = ? AND deleted = false AND code IN (%s)
                """.formatted(placeholders(codes.size())),
                (rs, rowNum) -> (UUID) rs.getObject(1), args.toArray());
    }

    /**
     * 按权限码解析权限主键（仅本租户）。
     *
     * @param tenantId 租户
     * @param codes 权限码
     * @return 命中的权限主键
     */
    public List<UUID> findPermissionIdsByCodes(UUID tenantId, Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        args.addAll(codes);
        return jdbcTemplate.query("""
                SELECT id FROM permission
                 WHERE tenant_id = ? AND deleted = false AND code IN (%s)
                """.formatted(placeholders(codes.size())),
                (rs, rowNum) -> (UUID) rs.getObject(1), args.toArray());
    }

    /**
     * 按登录名查询用户及其租户（忽略租户上下文，仅用于登录时解析真实租户）。
     *
     * <p>登录请求尚未建立租户上下文，这里显式扫描全部租户下的同名账号；
     * 调用方必须处理“多租户同名冲突”与“租户为空”两种结果。</p>
     *
     * @param username 登录名
     * @return 匹配的用户与租户（可能为空或多条）
     */
    public List<UsernameTenant> findUsersByUsername(String username) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id
                  FROM users
                 WHERE username = ? AND deleted = false
                """, (rs, rowNum) -> new UsernameTenant(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)), username);
    }

    /**
     * 全量替换用户角色（带租户守卫）。
     *
     * <p>{@code user_role} 无 {@code tenant_id} 列，因此先用 {@code users.tenant_id} 校验归属；
     * 目标用户不属于该租户时拒绝写入并抛出 {@link IllegalStateException}。</p>
     *
     * @param tenantId 租户
     * @param userId 用户主键
     * @param roleIds 目标角色集合
     */
    public void replaceUserRoles(UUID tenantId, UUID userId, Collection<UUID> roleIds) {
        requireUserInTenant(tenantId, userId);
        for (UUID roleId : new LinkedHashSet<>(roleIds)) {
            requireRoleInTenant(tenantId, roleId);
        }
        jdbcTemplate.update("""
                DELETE FROM user_role ur
                 WHERE ur.user_id = ?
                   AND EXISTS (SELECT 1 FROM users u
                                WHERE u.id = ? AND u.tenant_id = ? AND u.deleted = false)
                """, userId, userId, tenantId);
        for (UUID roleId : new LinkedHashSet<>(roleIds)) {
            jdbcTemplate.update("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        }
    }

    /**
     * 全量替换角色权限（带租户守卫）。
     *
     * <p>{@code role_permission} 无 {@code tenant_id} 列，先校验 {@code role.tenant_id} 归属。</p>
     *
     * @param tenantId 租户
     * @param roleId 角色主键
     * @param permissionIds 目标权限集合
     */
    public void replaceRolePermissions(UUID tenantId, UUID roleId, Collection<UUID> permissionIds) {
        requireRoleInTenant(tenantId, roleId);
        jdbcTemplate.update("""
                DELETE FROM role_permission rp
                 WHERE rp.role_id = ?
                   AND EXISTS (SELECT 1 FROM role r
                                WHERE r.id = ? AND r.tenant_id = ? AND r.deleted = false)
                """, roleId, roleId, tenantId);
        for (UUID permissionId : new LinkedHashSet<>(permissionIds)) {
            jdbcTemplate.update("INSERT INTO role_permission (role_id, permission_id) VALUES (?, ?)",
                    roleId, permissionId);
        }
    }

    private void requireUserInTenant(UUID tenantId, UUID userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM users
                 WHERE id = ? AND tenant_id = ? AND deleted = false
                """, Integer.class, userId, tenantId);
        if (count == null || count == 0) {
            throw new IllegalStateException("跨租户用户角色替换被拒绝");
        }
    }

    private void requireRoleInTenant(UUID tenantId, UUID roleId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM role
                 WHERE id = ? AND tenant_id = ? AND deleted = false
                """, Integer.class, roleId, tenantId);
        if (count == null || count == 0) {
            throw new IllegalStateException("跨租户角色操作被拒绝");
        }
    }

    /**
     * 登录名扫描结果。
     *
     * @param userId 用户主键
     * @param tenantId 所属租户（DDL 允许为空，登录时按 dev/prod 分别处理）
     */
    public record UsernameTenant(UUID userId, UUID tenantId) {
    }

    /**
     * 判断用户是否拥有指定角色码。
     *
     * @param tenantId 租户
     * @param userId 用户主键
     * @param roleCode 角色码
     * @return true 表示拥有
     */
    public boolean userHasRole(UUID tenantId, UUID userId, String roleCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                  FROM user_role ur
                  JOIN role r ON r.id = ur.role_id
                 WHERE ur.user_id = ? AND r.tenant_id = ? AND r.code = ? AND r.deleted = false
                """, Integer.class, userId, tenantId, roleCode);
        return count != null && count > 0;
    }

    /**
     * 统计租户内 ACTIVE 状态的 ADMIN 用户数。
     *
     * @param tenantId 租户
     * @return 数量
     */
    public long countActiveAdminUsers(UUID tenantId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT u.id)
                  FROM users u
                  JOIN user_role ur ON ur.user_id = u.id
                  JOIN role r ON r.id = ur.role_id
                 WHERE u.tenant_id = ? AND u.deleted = false AND u.status = 'ACTIVE'
                   AND r.tenant_id = ? AND r.code = 'ADMIN' AND r.deleted = false
                """, Long.class, tenantId, tenantId);
        return count == null ? 0L : count;
    }

    private static String placeholders(int size) {
        return String.join(", ", java.util.Collections.nCopies(size, "?"));
    }
}
