package com.hengce.aps.domain.masterdata.resource;

/**
 * 资源类型，对应 materials.yaml 的 ResourceType 与 V1 resource.type 列取值。
 *
 * <p>落库使用枚举名（VARCHAR），由 MyBatis 默认 EnumTypeHandler 处理。</p>
 */
public enum ResourceType {

    /** 主资源。 */
    PRIMARY,

    /** 辅资源。 */
    SECONDARY,

    /** 人力资源。 */
    HUMAN,

    /** 工具资源。 */
    TOOL
}
