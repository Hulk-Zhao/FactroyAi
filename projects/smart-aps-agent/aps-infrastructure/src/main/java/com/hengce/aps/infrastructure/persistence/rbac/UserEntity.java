package com.hengce.aps.infrastructure.persistence.rbac;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.infrastructure.persistence.handler.JsonbTypeHandler;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户持久化实体，字段对齐 V1 迁移的 {@code users} 表（C30）。
 *
 * <p>{@code password_hash} 只用于登录校验，任何响应 DTO 都不得携带该字段。</p>
 */
@TableName(value = "users", autoResultMap = true)
public class UserEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 登录名，租户内唯一。 */
    @TableField("username")
    private String username;

    /** 显示名。 */
    @TableField("display_name")
    private String displayName;

    /** bcrypt 口令哈希，LDAP/SSO 用户可为空。 */
    @TableField("password_hash")
    private String passwordHash;

    /** 邮箱。 */
    @TableField("email")
    private String email;

    /** 手机号。 */
    @TableField("phone")
    private String phone;

    /** 状态 ACTIVE/DISABLED/LOCKED。 */
    @TableField("status")
    private String status;

    /** 锁定截止时间（仅 LOCKED 有效）。 */
    @TableField("locked_until")
    private OffsetDateTime lockedUntil;

    /** 最近登录时间。 */
    @TableField("last_login_at")
    private OffsetDateTime lastLoginAt;

    /** 扩展属性，登录失败计数落在此 JSONB 列（不新增表/列）。 */
    @TableField(value = "ext_attrs", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> extAttrs = new LinkedHashMap<>();

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(OffsetDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(OffsetDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Map<String, Object> getExtAttrs() {
        if (extAttrs == null) {
            extAttrs = new LinkedHashMap<>();
        }
        return extAttrs;
    }

    public void setExtAttrs(Map<String, Object> extAttrs) {
        this.extAttrs = extAttrs == null ? new LinkedHashMap<>() : extAttrs;
    }
}
