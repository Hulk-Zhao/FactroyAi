package com.hengce.aps.solver.timefold.constraint;

import com.hengce.aps.solver.timefold.dependency.TaskDependency;
import com.hengce.aps.solver.timefold.model.TaskPlan;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 硬约束判定谓词：约束流与归因分析共用，避免两处口径漂移。
 */
final class ConstraintPredicates {

    private ConstraintPredicates() {
    }

    /**
     * 任务是否未被排位：没有资源序列归属，或没有开始时间。
     *
     * <p>{@code forEach} 会静默跳过未初始化实体，因此未排位任务必须走
     * {@code forEachIncludingUnassigned} + 本谓词才能被惩罚，否则部分解会被判为可行。</p>
     *
     * @param task 任务
     * @return {@code true} 表示未排位
     */
    static boolean unassigned(TaskPlan task) {
        return task.getResourceId() == null || task.getStartTime() == null;
    }

    /**
     * 同一资源上的占用切片是否存在重叠（半开区间 {@code [start, end)}）。
     *
     * @param left 切片一
     * @param right 切片二
     * @return {@code true} 表示重叠
     */
    static boolean slotOverlap(TaskPlan.OccupancySlot left, TaskPlan.OccupancySlot right) {
        return left.start().isBefore(right.end()) && right.start().isBefore(left.end());
    }

    /**
     * 统计同一资源分组内的重叠切片对数。
     *
     * @param slots 同一资源的全部占用切片
     * @return 重叠对数
     */
    static int overlapPairCount(List<TaskPlan.OccupancySlot> slots) {
        int count = 0;
        for (int first = 0; first < slots.size(); first++) {
            for (int second = first + 1; second < slots.size(); second++) {
                if (slotOverlap(slots.get(first), slots.get(second))) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 两个任务是否存在共享资源上的占用重叠（主资源含换型、辅助资源仅加工窗口）。
     *
     * @param first 任务一
     * @param second 任务二
     * @return {@code true} 表示重叠
     */
    static boolean resourceOverlap(TaskPlan first, TaskPlan second) {
        if (first.getStartTime() == null || second.getStartTime() == null) {
            return false;
        }
        for (UUID resourceId : first.getOccupiedResourceIds()) {
            if (!second.getOccupiedResourceIds().contains(resourceId)) {
                continue;
            }
            LocalDateTime firstStart = first.getOccupancyStart(resourceId);
            LocalDateTime secondStart = second.getOccupancyStart(resourceId);
            if (firstStart != null && secondStart != null
                    && firstStart.isBefore(second.getEndTime())
                    && secondStart.isBefore(first.getEndTime())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 任务占用块未完整落在可用时段内。
     *
     * @param task 任务
     * @return {@code true} 表示违反日历
     */
    static boolean calendarViolated(TaskPlan task) {
        return task.getStartTime() != null && !task.isWithinAvailability();
    }

    /**
     * 依赖约束是否被违反。
     *
     * @param dependency 依赖边
     * @param successor 后继任务
     * @param predecessor 前序任务
     * @return {@code true} 表示违反
     */
    static boolean dependencyViolated(
            TaskDependency dependency, TaskPlan successor, TaskPlan predecessor) {
        return dependency.violatedBy(
                predecessor.getStartTime(),
                predecessor.getEndTime(),
                successor.getStartTime(),
                successor.getEndTime());
    }

    /**
     * 跨窗口下界是否被违反：任务开始时间早于订单窗口外 FROZEN 任务的最大结束时间。
     *
     * <p>{@code orderNotBefore} 为空或任务未排位时不判定，不产生惩罚。</p>
     *
     * @param task 任务
     * @return {@code true} 表示违反
     */
    static boolean orderNotBeforeViolated(TaskPlan task) {
        LocalDateTime lowerBound = task.getOrderNotBefore();
        return lowerBound != null
                && task.getStartTime() != null
                && task.getStartTime().isBefore(lowerBound);
    }

    /**
     * 有效锁类型（C21 §24.3 优先级 {@code FROZEN > lockType > frozen:boolean}）。
     *
     * <p>{@link TaskPlan} 没有任务状态字段，{@code lockType=FROZEN} 即 FROZEN 层；
     * M1 的 {@code frozen} 布尔是 {@code lockType=FROZEN} 的兼容别名，优先级最低：
     * 只要显式 {@code lockType} 不是 {@code NONE}，就以 {@code lockType} 为准，
     * 只有 {@code lockType} 缺省/{@code NONE} 时才回退到布尔别名（C21 原文
     * “旧客户端只传 {@code frozen} 时行为与 M1 一致”）。一旦落到 FROZEN，
     * 就不再叠加 TIME_WINDOW/RESOURCE 判定，避免同一任务被重复罚一次。</p>
     *
     * @param task 任务
     * @return 有效锁类型
     */
    static String effectiveLockType(TaskPlan task) {
        String lockType = task.getLockType();
        if (lockType != null && !ConstraintCodes.LOCK_NONE.equals(lockType)) {
            return lockType;
        }
        return task.isFrozen() ? ConstraintCodes.LOCK_FROZEN : ConstraintCodes.LOCK_NONE;
    }

    /**
     * 冻结任务的资源被移动。
     *
     * @param task 任务
     * @return {@code true} 表示违反
     */
    static boolean frozenResourceViolated(TaskPlan task) {
        return ConstraintCodes.LOCK_FROZEN.equals(effectiveLockType(task))
                && task.getPinnedResourceId() != null
                && (task.getResourceId() == null
                || !task.getPinnedResourceId().equals(task.getResourceId()));
    }

    /**
     * 冻结任务的开始时间被移动。
     *
     * @param task 任务
     * @return {@code true} 表示违反
     */
    static boolean frozenStartTimeViolated(TaskPlan task) {
        return ConstraintCodes.LOCK_FROZEN.equals(effectiveLockType(task))
                && task.getPinnedStartTime() != null
                && !task.getPinnedStartTime().equals(task.getStartTime());
    }

    /**
     * C21 时间窗锁是否被违反：任务占用块（含换型）必须完整落在
     * {@code [lockedFrom, lockedTo]} 内，<b>两端均为闭区间</b>（C21 §24.3 原文
     * “任务落在 {@code [from,to]} 内”）。{@code lockedFrom}/{@code lockedTo}
     * 任一缺失时静默失效（视为未锁定），不产生惩罚。
     *
     * @param task 任务
     * @return {@code true} 表示违反
     */
    static boolean timeWindowLockViolated(TaskPlan task) {
        if (!ConstraintCodes.LOCK_TIME_WINDOW.equals(effectiveLockType(task))) {
            return false;
        }
        LocalDateTime from = task.getLockedFrom();
        LocalDateTime to = task.getLockedTo();
        if (from == null || to == null) {
            return false;
        }
        if (task.getStartTime() == null || task.getEndTime() == null) {
            return false;
        }
        return task.getStartTime().isBefore(from) || task.getEndTime().isAfter(to);
    }

    /**
     * C21 资源锁是否被违反：任务主资源必须属于 {@code lockedResourceIds}。
     * 集合为空/null 视为未锁定，不产生惩罚。
     *
     * @param task 任务
     * @return {@code true} 表示违反
     */
    static boolean resourceLockViolated(TaskPlan task) {
        if (!ConstraintCodes.LOCK_RESOURCE.equals(effectiveLockType(task))) {
            return false;
        }
        if (task.getLockedResourceIds() == null || task.getLockedResourceIds().isEmpty()) {
            return false;
        }
        return task.getResourceId() == null || !task.getLockedResourceIds().contains(task.getResourceId());
    }

    /**
     * 任务被排到非主资源。
     *
     * @param task 任务
     * @return {@code true} 表示违反
     */
    static boolean primaryResourceViolated(TaskPlan task) {
        return task.getPrimaryResourceId() != null
                && task.getResourceId() != null
                && !task.getPrimaryResourceId().equals(task.getResourceId());
    }
}
