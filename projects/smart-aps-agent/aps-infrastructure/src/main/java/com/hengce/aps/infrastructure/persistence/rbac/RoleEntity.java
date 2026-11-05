package com.hengce.aps.infrastructure.persistence.rbac;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;

/**
 * 角色持久化实体，字段对齐 V1 迁移的 {@code role} 表（C30）。
 */
@TableName("role")
public class RoleEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 角色码，租户内唯一。 */
    @TableField("code")
    private String code;

    /** 角色名称。 */
    @TableField("name")
    private String name;

    /** 角色描述。 */
    @TableField("description")
    private String description;

    /** 是否内置角色（V1 种子 5 角色为 true）。 */
    @TableField("is_builtin")
    private Boolean builtin;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getBuiltin() {
        return builtin;
    }

    public void setBuiltin(Boolean builtin) {
        this.builtin = builtin;
    }
}
