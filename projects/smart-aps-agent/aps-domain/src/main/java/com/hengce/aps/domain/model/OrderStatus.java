package com.hengce.aps.domain.model;

/**
 * 生产订单状态，取值与 db/migration/V1__init.sql 中 orders.status 保持一致。
 */
public enum OrderStatus {

    /**
     * 草稿，可编辑。
     */
    DRAFT,

    /**
     * 已确认，可参与排产。
     */
    CONFIRMED,

    /**
     * 已排产。
     */
    SCHEDULED,

    /**
     * 已下达。
     */
    RELEASED,

    /**
     * 生产中。
     */
    IN_PROGRESS,

    /**
     * 已完成。
     */
    COMPLETED,

    /**
     * 已取消。
     */
    CANCELLED
}
