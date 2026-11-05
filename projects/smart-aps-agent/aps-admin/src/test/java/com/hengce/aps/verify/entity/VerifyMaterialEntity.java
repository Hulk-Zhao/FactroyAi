package com.hengce.aps.verify.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.infrastructure.persistence.handler.JsonbTypeHandler;
import java.util.Map;

/**
 * 仅本地真库回归使用的 material 实体（test scope，不进入生产包）。
 */
@TableName("material")
public class VerifyMaterialEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String code;

    private String name;

    private String type;

    private String unit;

    @TableField(value = "ext_attrs", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> extAttrs;

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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Map<String, Object> getExtAttrs() {
        return extAttrs;
    }

    public void setExtAttrs(Map<String, Object> extAttrs) {
        this.extAttrs = extAttrs;
    }
}
