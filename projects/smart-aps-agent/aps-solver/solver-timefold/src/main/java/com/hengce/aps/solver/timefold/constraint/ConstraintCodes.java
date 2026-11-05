package com.hengce.aps.solver.timefold.constraint;

import java.util.List;

/**
 * 约束编码常量：与 Constraint Stream 的 {@code asConstraint} 名称保持一致，
 * 供 {@code ConstraintJustification.constraintCode} 与测试共用。
 */
public final class ConstraintCodes {

    /**
     * 任务必须被排入某个资源序列且拥有开始时间；未排位任务按 1 次硬惩罚计。
     *
     * <p>这是“部分解不得冒充可行解”的兜底：{@code @PlanningListVariable} 默认
     * {@code allowsUnassignedValues=false}，但构造启发式超时返回的仍是初始解 /
     * 部分解，若不给未排位任务加硬惩罚，其得分为 {@code 0hard/0soft} 会被误判为可行。</p>
     */
    public static final String UNASSIGNED_TASK = "unassignedTask";

    /**
     * 资源（主资源含换型、辅助资源仅加工窗口）不重叠。
     */
    public static final String RESOURCE_NO_OVERLAP = "resourceNoOverlap";

    /**
     * 任务占用块必须完整落在资源可用时段内。
     */
    public static final String CALENDAR_AVAILABILITY = "calendarAvailability";

    /**
     * 工序依赖（FS/SS/FF 与滞后）。
     */
    public static final String OPERATION_DEPENDENCY = "operationDependency";

    /**
     * 跨窗口下界：订单窗口内工序不得早于该订单窗口外 FROZEN 任务的最大结束时间。
     */
    public static final String ORDER_NOT_BEFORE = "orderNotBefore";

    /**
     * 冻结任务资源不可变。
     */
    public static final String FROZEN_RESOURCE = "frozenResource";

    /**
     * 冻结任务开始时间不可变。
     */
    public static final String FROZEN_START_TIME = "frozenStartTime";

    /**
     * 任务只能排在其主资源上。
     */
    public static final String PRIMARY_RESOURCE_ONLY = "primaryResourceOnly";

    /**
     * C21 时间窗锁：任务占用块必须完整落在 {@code [lockedFrom, lockedTo]} 内（含端点）。
     */
    public static final String TIME_WINDOW_LOCK = "timeWindowLock";

    /**
     * C21 资源锁：任务主资源必须属于 {@code lockedResourceIds}。
     */
    public static final String RESOURCE_LOCK = "resourceLock";

    /**
     * C21 未锁定。
     */
    public static final String LOCK_NONE = "NONE";

    /**
     * C21 时间窗锁类型。
     */
    public static final String LOCK_TIME_WINDOW = "TIME_WINDOW";

    /**
     * C21 资源锁类型。
     */
    public static final String LOCK_RESOURCE = "RESOURCE";

    /**
     * C21 全冻结锁类型（优先级最高）。
     */
    public static final String LOCK_FROZEN = "FROZEN";

    /**
     * 软约束：最小超期。
     */
    public static final String MIN_TARDINESS = "MIN_TARDINESS";

    /**
     * 软约束：交期优先。
     */
    public static final String EDD = "EDD";

    /**
     * 软约束：短任务优先。
     */
    public static final String SPT = "SPT";

    /**
     * 软约束：同品连续生产。
     */
    public static final String SAME_PRODUCT_CONTINUOUS = "SAME_PRODUCT_CONTINUOUS";

    /**
     * 软约束：最小换型时长。
     */
    public static final String MIN_SETUP_TIME = "MIN_SETUP_TIME";

    /**
     * 软约束：均衡负载率。
     */
    public static final String BALANCE_LOAD = "BALANCE_LOAD";

    /**
     * 软约束：优先级尊重。
     */
    public static final String PRIORITY_RESPECT = "PRIORITY_RESPECT";

    /**
     * 软约束：最小总完工时间。
     */
    public static final String MIN_MAKESPAN = "MIN_MAKESPAN";

    /**
     * 8 条核心软约束编码，顺序与 DSL 白名单一致。
     */
    public static final List<String> SOFT_RULE_CODES = List.of(
            MIN_TARDINESS,
            EDD,
            SPT,
            SAME_PRODUCT_CONTINUOUS,
            MIN_SETUP_TIME,
            BALANCE_LOAD,
            PRIORITY_RESPECT,
            MIN_MAKESPAN);

    private ConstraintCodes() {
    }
}
