package com.hengce.aps.solver.timefold;

import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.OperationDependency;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SetupMatrixEntry;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.TaskAssignment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 求解测试夹具：合成订单/资源/冻结分配，避免各测试重复构造。
 */
public final class SolverFixtures {

    private SolverFixtures() {
    }

    /**
     * 求解配置。
     *
     * @param seconds 超时秒数
     * @return 配置
     */
    public static SolveConfig config(int seconds) {
        return new SolveConfig(
                "timefold",
                seconds,
                1,
                "LATE_ACCEPTANCE",
                1L,
                0L,
                0,
                "FORWARD",
                true,
                Map.of());
    }

    /**
     * 构造问题（无换型矩阵）。
     *
     * @param orders 订单
     * @param resources 资源
     * @param frozen 冻结分配
     * @return 问题
     */
    public static ScheduleProblem problem(
            List<SchedulingOrder> orders,
            List<SchedulingResource> resources,
            List<TaskAssignment> frozen) {
        return problem(orders, resources, frozen, List.of());
    }

    /**
     * 构造问题（含换型矩阵）。
     *
     * @param orders 订单
     * @param resources 资源
     * @param frozen 冻结分配
     * @param setupMatrix 换型矩阵
     * @return 问题
     */
    public static ScheduleProblem problem(
            List<SchedulingOrder> orders,
            List<SchedulingResource> resources,
            List<TaskAssignment> frozen,
            List<SetupMatrixEntry> setupMatrix) {
        return new ScheduleProblem(
                "tenant-demo",
                ScheduleProblem.DEFAULT_SANDBOX_ID,
                null,
                null,
                "FORWARD",
                orders,
                resources,
                frozen,
                List.of(),
                setupMatrix);
    }

    /**
     * 构造 7×24 资源。
     *
     * @param resourceId 主键
     * @param code 编码
     * @return 资源
     */
    public static SchedulingResource resource(UUID resourceId, String code) {
        return resource(resourceId, code, List.of());
    }

    /**
     * 构造带可用时段的资源。
     *
     * @param resourceId 主键
     * @param code 编码
     * @param windows 可用时段
     * @return 资源
     */
    public static SchedulingResource resource(
            UUID resourceId, String code, List<AvailabilityWindow> windows) {
        return new SchedulingResource(
                resourceId,
                code,
                "PRIMARY",
                null,
                "SECONDS_PER_UNIT",
                BigDecimal.ONE,
                null,
                BigDecimal.ONE,
                null,
                "ACTIVE",
                windows);
    }

    /**
     * 构造辅助资源。
     *
     * @param resourceId 主键
     * @param code 编码
     * @return 资源
     */
    public static SchedulingResource secondaryResource(UUID resourceId, String code) {
        return new SchedulingResource(
                resourceId,
                code,
                "SECONDARY",
                null,
                "SECONDS_PER_UNIT",
                BigDecimal.ONE,
                null,
                BigDecimal.ONE,
                null,
                "ACTIVE",
                List.of());
    }

    /**
     * 构造一笔订单。
     *
     * @param orderNo 订单号
     * @param operations 工序
     * @return 订单
     */
    public static SchedulingOrder order(String orderNo, List<SchedulingOperation> operations) {
        return new SchedulingOrder(
                UUID.randomUUID(),
                orderNo,
                "MAT-" + orderNo,
                BigDecimal.valueOf(12),
                LocalDate.now(ZoneOffset.UTC).plusDays(3),
                "NORMAL",
                "UNFROZEN",
                UUID.randomUUID(),
                operations);
    }

    /**
     * 构造一道工序。
     *
     * @param operationIndex 工序序号
     * @param primaryResourceId 主资源
     * @return 工序
     */
    public static SchedulingOperation operation(int operationIndex, UUID primaryResourceId) {
        return operation(operationIndex, primaryResourceId, List.of(), List.of());
    }

    /**
     * 构造带前序依赖的工序。
     *
     * @param operationIndex 工序序号
     * @param primaryResourceId 主资源
     * @param predecessors 前序依赖
     * @return 工序
     */
    public static SchedulingOperation operation(
            int operationIndex, UUID primaryResourceId, List<OperationDependency> predecessors) {
        return operation(operationIndex, primaryResourceId, List.of(), predecessors);
    }

    /**
     * 构造带辅助资源与前序依赖的工序。
     *
     * @param operationIndex 工序序号
     * @param primaryResourceId 主资源
     * @param secondaryResourceIds 辅助资源
     * @param predecessors 前序依赖
     * @return 工序
     */
    public static SchedulingOperation operation(
            int operationIndex,
            UUID primaryResourceId,
            List<UUID> secondaryResourceIds,
            List<OperationDependency> predecessors) {
        return new SchedulingOperation(
                operationIndex,
                "OP-" + operationIndex,
                "工序 " + operationIndex,
                null,
                BigDecimal.valueOf(600),
                BigDecimal.valueOf(600),
                primaryResourceId,
                null,
                secondaryResourceIds,
                false,
                predecessors);
    }

    /**
     * 构造冻结分配。
     *
     * @param order 订单
     * @param operationIndex 工序序号
     * @param resourceId 资源
     * @param resourceCode 资源编码
     * @param setupStart 占用开始时间
     * @param durationSeconds 占用总时长
     * @param secondaryResourceIds 辅助资源
     * @return 冻结分配
     */
    public static TaskAssignment frozen(
            SchedulingOrder order,
            int operationIndex,
            UUID resourceId,
            String resourceCode,
            LocalDateTime setupStart,
            long durationSeconds,
            List<UUID> secondaryResourceIds) {
        OffsetDateTime start = setupStart.atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(durationSeconds);
        return new TaskAssignment(
                null,
                order.orderId(),
                order.orderNo(),
                operationIndex,
                "工序 " + operationIndex,
                null,
                resourceId,
                resourceCode,
                secondaryResourceIds,
                start,
                start.plusSeconds(600),
                start.plusSeconds(600),
                end,
                BigDecimal.valueOf(12),
                0,
                "FROZEN",
                true,
                1);
    }

    /**
     * 以当前 UTC 整点为基准的时间。
     *
     * @param plusHours 偏移小时
     * @return 时间
     */
    public static LocalDateTime baseTime(int plusHours) {
        return LocalDateTime.now(ZoneOffset.UTC)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .plusHours(plusHours);
    }
}
