package com.hengce.aps.service.masterdata.order;

/**
 * 订单来源枚举，取值与 {@code orders.source} 的 CHECK 约束一致（见 docs/api/enums.md）。
 */
public enum OrderSource {

    /** ERP 同步。 */
    ERP_SYNC,

    /** 手工创建。 */
    MANUAL,

    /** Agent 创建。 */
    AGENT
}
