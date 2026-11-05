package com.hengce.aps.service.masterdata.order;

/**
 * 订单优先级枚举，取值与 {@code orders.priority} 的 CHECK 约束一致（见 docs/api/enums.md）。
 */
public enum OrderPriority {

    /** 紧急。 */
    URGENT,

    /** 高。 */
    HIGH,

    /** 普通（默认）。 */
    NORMAL,

    /** 低。 */
    LOW
}
