package com.hengce.aps.domain.masterdata.resource;

/**
 * 资源产能模式，对应 materials.yaml 的 CapacityMode 与 V1 resource.capacity_mode 列取值。
 *
 * <p>三种模式的实际换算口径见 contract-freeze C2：</p>
 * <ul>
 *   <li>{@code UNIT_PER_HOUR}：capacity_value 表示每小时产能（单位/小时）；</li>
 *   <li>{@code SECONDS_PER_UNIT}：capacity_value 不参与计算，单件工时取标准工时；</li>
 *   <li>{@code BATCH_SECONDS}：capacity_value 表示每批工时（秒），batch_capacity 为批量。</li>
 * </ul>
 */
public enum CapacityMode {

    /** 每小时产出单位数。 */
    UNIT_PER_HOUR,

    /** 每单位标准秒数。 */
    SECONDS_PER_UNIT,

    /** 每批秒数。 */
    BATCH_SECONDS
}
