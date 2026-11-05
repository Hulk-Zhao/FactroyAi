package com.hengce.aps.service.rbac;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengce.aps.infrastructure.persistence.mapper.rbac.PermissionMapper;
import com.hengce.aps.infrastructure.persistence.rbac.PermissionEntity;
import com.hengce.aps.infrastructure.persistence.rbac.RbacRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 权限解析与 60 秒缓存（C30 / 25.1.3）。
 *
 * <p>JWT 每次请求按 {@code sub} 解析权限并集，进程内缓存 60 秒；角色权限变更时主动失效。</p>
 */
@Service
public class PermissionService {

    /**
     * 权限缓存有效期。
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final RbacRepository rbacRepository;

    private final PermissionMapper permissionMapper;

    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 构造服务。
     *
     * @param rbacRepository RBAC 关联持久层
     * @param permissionMapper 权限 Mapper
     */
    public PermissionService(RbacRepository rbacRepository, PermissionMapper permissionMapper) {
        this.rbacRepository = rbacRepository;
        this.permissionMapper = permissionMapper;
    }

    /**
     * 查询用户权限码并集（带 60 秒缓存）。
     *
     * @param userId 用户主键
     * @return 权限码
     */
    public List<String> permissionCodesForUser(UUID userId) {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(userId);
        if (entry != null && now - entry.loadedAt() < CACHE_TTL.toMillis()) {
            return entry.codes();
        }
        List<String> codes = rbacRepository.findPermissionCodesByUserId(
                com.hengce.aps.common.tenant.TenantContext.getTenantId(), userId);
        cache.put(userId, new CacheEntry(List.copyOf(codes), now));
        return List.copyOf(codes);
    }

    /**
     * 判断用户是否拥有权限码。
     *
     * @param userId 用户主键
     * @param permissionCode 权限码
     * @return true 表示拥有
     */
    public boolean hasPermission(UUID userId, String permissionCode) {
        return permissionCodesForUser(userId).contains(permissionCode);
    }

    /**
     * 失效单个用户缓存。
     *
     * @param userId 用户主键
     */
    public void invalidate(UUID userId) {
        if (userId != null) {
            cache.remove(userId);
        }
    }

    /**
     * 失效全部用户缓存（角色权限变更后调用）。
     */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * 列出全部权限点（18 个 V1 种子），供前端渲染角色授权。
     *
     * @return 权限点
     */
    public List<PermissionView> listAll() {
        return permissionMapper.selectList(Wrappers.lambdaQuery(PermissionEntity.class)
                        .orderByAsc(PermissionEntity::getModule)
                        .orderByAsc(PermissionEntity::getCode))
                .stream()
                .map(entity -> new PermissionView((UUID) entity.getId(), entity.getCode(), entity.getName(),
                        entity.getModule(), entity.getDescription()))
                .toList();
    }

    private record CacheEntry(List<String> codes, long loadedAt) {
    }
}
