package com.hengce.aps.infrastructure.persistence.rbac;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;

/**
 * 权限点持久化实体，字段对齐 V1 迁移的 {@code permission} 表（C30）。
 */
@TableName("permission")
public class PermissionEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 权限码，租户内唯一（V1 共 18 个，不得新增）。 */
    @TableField("code")
    private String code;

    /** 权限名称。 */
    @TableField("name")
    private String name;

    /** 所属模块。 */
    @TableField("module")
    private String module;

    /** 权限描述。 */
    @TableField("description")
    private String description;

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

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
