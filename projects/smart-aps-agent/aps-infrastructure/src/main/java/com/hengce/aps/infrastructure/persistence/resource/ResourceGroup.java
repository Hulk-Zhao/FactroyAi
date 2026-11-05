package com.hengce.aps.infrastructure.persistence.resource;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import java.util.UUID;

/**
 * 资源组持久化实体，字段对齐 V1 迁移的 resource_group 表。
 *
 * <p>与 {@link Resource} 同包，避免 checkstyle 的包名正则禁止下划线包名；
 * 资源组不单独提供 CRUD 端点，仅支撑 {@code resource.groupId} 的引用校验与筛选。</p>
 */
@TableName("resource_group")
public class ResourceGroup extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 资源组编码（租户内未删除记录唯一）。 */
    @TableField("code")
    private String code;

    /** 资源组名称。 */
    @TableField("name")
    private String name;

    /** 描述。 */
    @TableField("description")
    private String description;

    /** 绑定的工作日历主键。 */
    @TableField("calendar_id")
    private UUID calendarId;

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

    public UUID getCalendarId() {
        return calendarId;
    }

    public void setCalendarId(UUID calendarId) {
        this.calendarId = calendarId;
    }
}
