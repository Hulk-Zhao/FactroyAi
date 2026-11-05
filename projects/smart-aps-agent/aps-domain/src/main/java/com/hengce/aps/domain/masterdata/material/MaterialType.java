package com.hengce.aps.domain.masterdata.material;

/**
 * 物料类型，对应 materials.yaml 的 MaterialType 与 V1 material.type 列取值。
 *
 * <p>落库使用枚举名（VARCHAR），由 MyBatis 默认 EnumTypeHandler 处理。</p>
 */
public enum MaterialType {

    /** 成品。 */
    FINISHED,

    /** 半成品。 */
    SEMI,

    /** 原材料。 */
    RAW,

    /** 辅料。 */
    AUX,

    /** 包材。 */
    PACK
}
