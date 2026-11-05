package com.hengce.aps.service.masterdata.bom;

/**
 * BOM 状态枚举，取值与 {@code bom.status} 的 CHECK 约束一致（见 docs/api/enums.md）。
 *
 * <p>持久化层以 VARCHAR 存储，服务层负责名字转换；API 传输值使用大写字符串。</p>
 */
public enum BomStatus {

    /** 草稿：可编辑，不可被订单确认引用。 */
    DRAFT,

    /** 生效：同一父件仅允许一个 ACTIVE（部分唯一索引 uq_bom_one_active_per_parent）。 */
    ACTIVE,

    /** 失效：历史版本，只读。 */
    INACTIVE
}
