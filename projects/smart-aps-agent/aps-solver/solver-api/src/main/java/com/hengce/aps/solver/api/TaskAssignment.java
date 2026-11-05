package com.hengce.aps.solver.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 排产分配结果，也是求解器计划实体的中立投影。
 *
 * <p>时间字段一律使用 UTC（{@link java.time.ZoneOffset#UTC}）；
 * {@code startTime}/{@code endTime} 为加工窗口，{@code setupStartTime}/{@code setupEndTime}
 * 为换型窗口，{@code setupSeconds = 0} 时三者相等。</p>
 *
 * @param taskId 排产任务主键；新解为空，持久化后回填
 * @param orderId 订单主键
 * @param orderNo 订单号
 * @param operationIndex 工序序号
 * @param operationName 工序名称
 * @param operationTemplateId 工序模板标识，可空
 * @param resourceId 主资源标识，可空
 * @param resourceCode 主资源编码
 * @param secondaryResourceIds 辅助资源标识
 * @param setupStartTime 换型开始时间
 * @param setupEndTime 换型结束时间
 * @param startTime 加工开始时间，等于 {@code setupEndTime}
 * @param endTime 加工结束时间
 * @param qty 数量
 * @param sequenceInResource 资源内顺序
 * @param status 任务状态：PLANNED / FROZEN / IN_PROGRESS / COMPLETED
 * @param frozen 是否冻结/锁定（对应人工锁定的 pinned 语义）
 * @param version 乐观锁版本
 * @param lockType C21 锁类型：NONE / TIME_WINDOW / RESOURCE / FROZEN，可空
 * @param lockedFrom 时间窗锁起点（闭区间，可空）
 * @param lockedTo 时间窗锁终点（闭区间，可空）
 * @param lockedResourceIds 资源锁允许的主资源集合，空集合视为未锁定
 */
public record TaskAssignment(
        Long taskId,
        UUID orderId,
        String orderNo,
        int operationIndex,
        String operationName,
        UUID operationTemplateId,
        UUID resourceId,
        String resourceCode,
        List<UUID> secondaryResourceIds,
        OffsetDateTime setupStartTime,
        OffsetDateTime setupEndTime,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        BigDecimal qty,
        Integer sequenceInResource,
        String status,
        boolean frozen,
        Integer version,
        String lockType,
        OffsetDateTime lockedFrom,
        OffsetDateTime lockedTo,
        List<UUID> lockedResourceIds
) {

    /**
     * 兼容既有 18 参数调用（不含 C21 锁字段）。
     */
    public TaskAssignment(Long taskId, UUID orderId, String orderNo, int operationIndex,
                          String operationName, UUID operationTemplateId, UUID resourceId,
                          String resourceCode, List<UUID> secondaryResourceIds,
                          OffsetDateTime setupStartTime, OffsetDateTime setupEndTime,
                          OffsetDateTime startTime, OffsetDateTime endTime, BigDecimal qty,
                          Integer sequenceInResource, String status, boolean frozen, Integer version) {
        this(taskId, orderId, orderNo, operationIndex, operationName, operationTemplateId,
                resourceId, resourceCode, secondaryResourceIds, setupStartTime, setupEndTime,
                startTime, endTime, qty, sequenceInResource, status, frozen, version,
                null, null, null, List.of());
    }

    /**
     * 归一化辅助资源与锁资源列表。
     */
    public TaskAssignment {
        secondaryResourceIds = List.copyOf(
                secondaryResourceIds == null ? List.of() : secondaryResourceIds);
        lockedResourceIds = List.copyOf(
                lockedResourceIds == null ? List.of() : lockedResourceIds);
    }
}
