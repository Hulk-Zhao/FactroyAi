package com.hengce.aps.service.schedule.atp;

/**
 * 瓶颈条目。
 *
 * @param type           RESOURCE_QUEUE/CALENDAR_GAP/OPERATION_DURATION/CRITICAL_OPERATION/
 *                       DUE_DATE_MISS/SOLVE_TIMEOUT/INFEASIBLE/INFEASIBLE_HORIZON/NO_RESOURCE
 * @param resourceCode   资源编码，可为 null
 * @param operationCode  工序编码，可为 null
 * @param operationIndex 工序序号，可为 null
 * @param delayMinutes   该瓶颈造成的等待分钟数，0 表示仅说明关键路径
 * @param detail         可读说明
 */
public record AtpBottleneck(
        String type,
        String resourceCode,
        String operationCode,
        Integer operationIndex,
        int delayMinutes,
        String detail) {
}
