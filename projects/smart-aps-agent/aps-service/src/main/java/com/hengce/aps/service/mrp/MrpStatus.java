package com.hengce.aps.service.mrp;

/**
 * MRP 齐套结果分级（C25）。
 *
 * <ul>
 *   <li>{@link #FULL}：所有订单的全部需求物料均满足；</li>
 *   <li>{@link #PARTIAL}：部分订单齐套、部分订单存在缺料；</li>
 *   <li>{@link #SHORTAGE}：所有订单均存在缺料。</li>
 * </ul>
 */
public enum MrpStatus {

    /** 全部齐套。 */
    FULL,

    /** 部分订单齐套。 */
    PARTIAL,

    /** 全部订单缺料。 */
    SHORTAGE
}
