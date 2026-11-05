package com.hengce.aps.infrastructure.persistence.material;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import java.util.UUID;

/**
 * 物料分类持久化实体，字段对齐 V1 迁移的 material_category 表。
 */
@TableName("material_category")
public class MaterialCategory extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 上级分类，null 表示根分类。 */
    @TableField("parent_id")
    private UUID parentId;

    /** 分类编码。 */
    @TableField("code")
    private String code;

    /** 分类名称。 */
    @TableField("name")
    private String name;

    /** 排序号。 */
    @TableField("sort_no")
    private Integer sortNo;

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

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

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }
}
