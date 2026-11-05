package com.hengce.aps.verify.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * role_permission 表不含 tenant_id 列，用于验证租户拦截器的忽略表行为。
 */
@TableName("role_permission")
public class VerifyRolePermissionEntity {

    private UUID roleId;

    private UUID permissionId;

    private OffsetDateTime createdAt;

    public UUID getRoleId() {
        return roleId;
    }

    public void setRoleId(UUID roleId) {
        this.roleId = roleId;
    }

    public UUID getPermissionId() {
        return permissionId;
    }

    public void setPermissionId(UUID permissionId) {
        this.permissionId = permissionId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
