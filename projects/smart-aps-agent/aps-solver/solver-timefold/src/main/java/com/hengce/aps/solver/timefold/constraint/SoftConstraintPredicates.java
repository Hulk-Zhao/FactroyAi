package com.hengce.aps.solver.timefold.constraint;

import com.hengce.aps.solver.timefold.model.TaskPlan;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

/**
 * 8 条核心软约束的判定与惩罚量计算，供约束流与单测共用，避免口径漂移。
 *
 * <p>惩罚量单位统一为“分钟”（超期、换型、总完工时间）或“小时平方”（负载平方和），
 * 求解时再乘以该规则的权重。</p>
 */
final class SoftConstraintPredicates {

    /**
     * 交期视为当日 23:59:59 截止。
     */
    private static final LocalTime DUE_TIME = LocalTime.of(23, 59, 59);

    private static final Map<String, Integer> PRIORITY_RANK = Map.of(
            "URGENT", 3,
            "HIGH", 2,
            "NORMAL", 1,
            "LOW", 0);

    private static final int DEFAULT_PRIORITY_RANK = 1;

    private SoftConstraintPredicates() {
    }

    /**
     * 交期截止时刻。
     *
     * @param task 任务
     * @return 截止时刻；无交期时为空
     */
    static LocalDateTime dueDeadline(TaskPlan task) {
        return task.getDueDate() == null ? null : task.getDueDate().atTime(DUE_TIME);
    }

    /**
     * 超期分钟数。
     *
     * @param task 任务
     * @return 超期分钟数；未超期或无交期时为 0
     */
    static long tardinessMinutes(TaskPlan task) {
        LocalDateTime deadline = dueDeadline(task);
        LocalDateTime end = task.getEndTime();
        if (deadline == null || end == null || !end.isAfter(deadline)) {
            return 0;
        }
        long minutes = Duration.between(deadline, end).toMinutes();
        return Math.max(1, minutes);
    }

    /**
     * 交期优先被违反：同资源上前序任务交期更晚，导致更早交期的任务排在其后。
     *
     * @param previous 前序任务
     * @param current 当前任务
     * @return {@code true} 表示违反
     */
    static boolean dueOrderViolated(TaskPlan previous, TaskPlan current) {
        LocalDateTime previousDue = dueDeadline(previous);
        LocalDateTime currentDue = dueDeadline(current);
        return previousDue != null && currentDue != null && previousDue.isAfter(currentDue);
    }

    /**
     * 短任务优先被违反：同资源上前序任务加工时长更长。
     *
     * @param previous 前序任务
     * @param current 当前任务
     * @return {@code true} 表示违反
     */
    static boolean sptViolated(TaskPlan previous, TaskPlan current) {
        return previous.getRunSeconds() > current.getRunSeconds();
    }

    /**
     * 同品连续被违反：同资源上相邻任务产品族不同。
     *
     * @param previous 前序任务
     * @param current 当前任务
     * @return {@code true} 表示违反
     */
    static boolean familyChangeViolated(TaskPlan previous, TaskPlan current) {
        String previousFamily = previous.getProductFamily();
        String currentFamily = current.getProductFamily();
        return previousFamily != null && currentFamily != null && !previousFamily.equals(currentFamily);
    }

    /**
     * 换型惩罚分钟数。
     *
     * @param task 任务
     * @return 换型分钟数（向上取整）；无换型时为 0
     */
    static long setupMinutes(TaskPlan task) {
        long setupSeconds = task.getSetupSeconds();
        if (setupSeconds <= 0) {
            return 0;
        }
        return Math.max(1, (setupSeconds + 59) / 60);
    }

    /**
     * 资源负载平方（小时平方），固定总负载下最小化即均衡。
     *
     * @param loadSeconds 资源总加工秒数
     * @return 小时平方
     */
    static long loadHoursSquared(long loadSeconds) {
        if (loadSeconds <= 0) {
            return 0;
        }
        long hours = loadSeconds / 3600;
        return hours * hours;
    }

    /**
     * 优先级排序值，数值越大优先级越高。
     *
     * @param priority 优先级
     * @return 排序值，未知或为空按 NORMAL
     */
    static int priorityRank(String priority) {
        return priority == null ? DEFAULT_PRIORITY_RANK
                : PRIORITY_RANK.getOrDefault(priority, DEFAULT_PRIORITY_RANK);
    }

    /**
     * 优先级尊重被违反：同资源上前序任务优先级更低。
     *
     * @param previous 前序任务
     * @param current 当前任务
     * @return {@code true} 表示违反
     */
    static boolean priorityViolated(TaskPlan previous, TaskPlan current) {
        return priorityRank(current.getPriority()) > priorityRank(previous.getPriority());
    }

    /**
     * 总完工时间分钟数。
     *
     * @param start 最早开始
     * @param end 最晚结束
     * @return 分钟数
     */
    static long makespanMinutes(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return 0;
        }
        return Duration.between(start, end).toMinutes();
    }
}
