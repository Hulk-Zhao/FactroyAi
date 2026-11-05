package com.hengce.aps.solver.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 引擎中立的排产问题输入快照。
 *
 * <p>本类型只使用 JDK 基础类型，禁止引入 Timefold / OR-Tools 类型。
 * {@code tenantId}/{@code sandboxId} 必填；{@code solveTaskId} 可空（试算场景）；
 * 列表字段为空时统一归一化为空列表。</p>
 *
 * @param tenantId 租户标识
 * @param sandboxId 场景标识，缺省取 {@link #DEFAULT_SANDBOX_ID}
 * @param solveTaskId 求解任务主键，可空
 * @param strategyId 排产策略标识，可空
 * @param direction 排产方向：FORWARD / BACKWARD / BOTTLENECK
 * @param orders 订单快照
 * @param resources 资源快照
 * @param frozenAssignments 冻结/锁定的既有分配
 * @param constraints 约束快照
 * @param setupMatrix 换型矩阵条目（C12 §17.3，可空；空表示全部使用静态换型工时）
 * @param baseTime 求解基准时间（UTC，已向下取整到小时）；为空表示由求解器取当前时刻。
 *                 装配侧与求解器必须使用同一个基准时间，否则日历展开窗口与开始时间候选
 *                 会各自漂移，同一份数据在不同真实时刻得不到同一题面
 * @param initialAssignments 预热初始解（既有排产结果，普通分配而非冻结）；为空表示冷启动
 */
public record ScheduleProblem(
        String tenantId,
        UUID sandboxId,
        Long solveTaskId,
        UUID strategyId,
        String direction,
        List<SchedulingOrder> orders,
        List<SchedulingResource> resources,
        List<TaskAssignment> frozenAssignments,
        List<ConstraintSpec> constraints,
        List<SetupMatrixEntry> setupMatrix,
        LocalDateTime baseTime,
        List<TaskAssignment> initialAssignments
) {

    /**
     * 默认场景标识（contract-freeze C5）。
     */
    public static final UUID DEFAULT_SANDBOX_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000401");

    /**
     * 归一化列表字段，避免下游出现空指针。
     */
    public ScheduleProblem {
        orders = List.copyOf(orders == null ? List.of() : orders);
        resources = List.copyOf(resources == null ? List.of() : resources);
        frozenAssignments = List.copyOf(
                frozenAssignments == null ? List.of() : frozenAssignments);
        constraints = List.copyOf(constraints == null ? List.of() : constraints);
        setupMatrix = List.copyOf(setupMatrix == null ? List.of() : setupMatrix);
        initialAssignments = List.copyOf(
                initialAssignments == null ? List.of() : initialAssignments);
    }

    /**
     * 兼容 C12 之前的调用方，等价于「无换型矩阵」。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param solveTaskId 求解任务主键
     * @param strategyId 排产策略标识
     * @param direction 排产方向
     * @param orders 订单快照
     * @param resources 资源快照
     * @param frozenAssignments 冻结分配
     * @param constraints 约束快照
     */
    public ScheduleProblem(
            String tenantId,
            UUID sandboxId,
            Long solveTaskId,
            UUID strategyId,
            String direction,
            List<SchedulingOrder> orders,
            List<SchedulingResource> resources,
            List<TaskAssignment> frozenAssignments,
            List<ConstraintSpec> constraints) {
        this(tenantId, sandboxId, solveTaskId, strategyId, direction, orders, resources,
                frozenAssignments, constraints, List.of(), null, List.of());
    }

    /**
     * 兼容不含基准时间与预热初始解的调用方。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param solveTaskId 求解任务主键
     * @param strategyId 排产策略标识
     * @param direction 排产方向
     * @param orders 订单快照
     * @param resources 资源快照
     * @param frozenAssignments 冻结分配
     * @param constraints 约束快照
     * @param setupMatrix 换型矩阵
     */
    public ScheduleProblem(
            String tenantId,
            UUID sandboxId,
            Long solveTaskId,
            UUID strategyId,
            String direction,
            List<SchedulingOrder> orders,
            List<SchedulingResource> resources,
            List<TaskAssignment> frozenAssignments,
            List<ConstraintSpec> constraints,
            List<SetupMatrixEntry> setupMatrix) {
        this(tenantId, sandboxId, solveTaskId, strategyId, direction, orders, resources,
                frozenAssignments, constraints, setupMatrix, null, List.of());
    }
}
