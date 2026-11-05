package com.hengce.aps.solver.api;

import java.time.OffsetDateTime;

/**
 * HTTP 过渡排产结果：订单在资源上的时间窗口占用。
 *
 * <p>按 C7，时间字段为 ISO-8601 UTC（{@link java.time.ZoneOffset#UTC}）。</p>
 *
 * @param orderNo 订单号
 * @param resourceCode 资源编码
 * @param startTime 加工开始时间
 * @param endTime 加工结束时间
 */
public record ScheduledTask(
        String orderNo,
        String resourceCode,
        OffsetDateTime startTime,
        OffsetDateTime endTime) {
}
