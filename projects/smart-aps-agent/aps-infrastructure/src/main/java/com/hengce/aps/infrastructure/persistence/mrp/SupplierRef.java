package com.hengce.aps.infrastructure.persistence.mrp;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;

/**
 * 供应商只读引用实体，字段对齐 V1 迁移的 {@code supplier} 表。
 *
 * <p>MRP 只需回填物料主数据的默认供应商编码，因此仅映射 {@code code}；
 * 放在 {@code persistence.mrp} 以便复用该包的 Mapper 扫描入口，
 * 不改动主数据模块的实体与写入逻辑。</p>
 */
@TableName("supplier")
public class SupplierRef extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 供应商业务编码。 */
    @TableField("code")
    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
