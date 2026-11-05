package com.hengce.aps.domain.model;

/**
 * 订单冻结状态，取值与 db/migration/V1__init.sql 中 orders.freeze_status 保持一致。
 */
public enum FreezeStatus {

    /**
     * 未冻结。
     */
    UNFROZEN,

    /**
     * 已冻结。
     */
    FROZEN,

    /**
     * 锁定时间窗。
     */
    LOCKED_TIME_WINDOW,

    /**
     * 锁定资源。
     */
    LOCKED_RESOURCE
}
