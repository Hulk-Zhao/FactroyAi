package com.hengce.aps.service.schedule.atp;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 产能锁定建议（仅建议，不落主计划）。
 *
 * @param advisoryOnly  恒为 true
 * @param required      若要以该交期承诺，是否需要在确认后锁定产能
 * @param reason        说明
 * @param resourceCodes 需要关注的资源编码
 * @param from          建议锁定时段起点
 * @param to            建议锁定时段终点
 */
public record CapacityLockSuggestion(
        boolean advisoryOnly,
        boolean required,
        String reason,
        List<String> resourceCodes,
        OffsetDateTime from,
        OffsetDateTime to) {
}
