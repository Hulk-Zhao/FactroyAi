package com.hengce.aps.solver.timefold.constraint;

import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 软约束归因重放（M2-SE-05）：对最优解逐条重算 8 条核心软约束的违反量与扣分。
 *
 * <p>Timefold 2.8.0 社区版没有商业特性 {@code SolutionManager.analyze()} /
 * {@code ConstraintMatchTotal}，因此这里复用 {@link SoftConstraintPredicates} 与
 * {@link ApsConstraintProvider} 完全一致的判定口径，在求解结束、权重仍未
 * {@code disable()} 时重放。规则键严格使用 {@link ConstraintCodes} 的 RuleCode，
 * 不使用中文标题；扣分 = 求解时生效权重 × 违反量，正数表示被扣掉的软分。</p>
 *
 * <p>每条规则聚合为一行（最多 8 行）：{@code taskIds} 在求解期为空（新解任务尚无数据库主键），
 * 由 {@code ScheduleResultWriter} 根据 {@code description} 中的
 * {@code 订单号/工序序号} 标签回填。</p>
 */
public final class SoftConstraintJustifications {

    private SoftConstraintJustifications() {
    }

    /**
     * 重放软约束归因。
     *
     * @param plan 最优解；为空或未启用软约束时返回空列表
     * @return 软约束归因，按 {@link ConstraintCodes#SOFT_RULE_CODES} 顺序聚合
     */
    public static List<ConstraintJustification> from(SchedulePlan plan) {
        Map<String, Integer> weights = SoftConstraintWeights.active();
        if (plan == null || weights.isEmpty() || plan.getTasks().isEmpty()) {
            return List.of();
        }
        List<TaskPlan> tasks = plan.getTasks().stream()
                .filter(task -> task.getStartTime() != null)
                .toList();
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<ConstraintJustification> justifications = new ArrayList<>();
        addTardiness(justifications, tasks, weights);
        addAdjacent(justifications, tasks, weights, ConstraintCodes.EDD,
                (previous, current) -> SoftConstraintPredicates.dueOrderViolated(previous, current),
                "对相邻任务违反交期优先（前序任务交期更晚）");
        addAdjacent(justifications, tasks, weights, ConstraintCodes.SPT,
                (previous, current) -> SoftConstraintPredicates.sptViolated(previous, current),
                "对相邻任务违反短任务优先（前序任务加工更久）");
        addAdjacent(justifications, tasks, weights, ConstraintCodes.SAME_PRODUCT_CONTINUOUS,
                (previous, current) -> SoftConstraintPredicates.familyChangeViolated(previous, current),
                "对相邻任务违反同品连续生产");
        addSetup(justifications, tasks, weights);
        addBalance(justifications, tasks, weights);
        addAdjacent(justifications, tasks, weights, ConstraintCodes.PRIORITY_RESPECT,
                (previous, current) -> SoftConstraintPredicates.priorityViolated(previous, current),
                "对相邻任务违反优先级尊重（低优先级排在前面）");
        addMakespan(justifications, tasks, weights);
        return List.copyOf(justifications);
    }

    private static void addTardiness(List<ConstraintJustification> target, List<TaskPlan> tasks,
                                     Map<String, Integer> weights) {
        long minutes = 0;
        List<TaskPlan> violated = new ArrayList<>();
        for (TaskPlan task : tasks) {
            long tardiness = SoftConstraintPredicates.tardinessMinutes(task);
            if (tardiness > 0) {
                minutes += tardiness;
                violated.add(task);
            }
        }
        addRule(target, ConstraintCodes.MIN_TARDINESS, weights, violated, minutes,
                "累计超期 " + minutes + " 分钟");
    }

    private static void addAdjacent(List<ConstraintJustification> target, List<TaskPlan> tasks,
                                    Map<String, Integer> weights, String code,
                                    java.util.function.BiPredicate<TaskPlan, TaskPlan> violated,
                                    String detail) {
        long pairs = 0;
        List<TaskPlan> implicated = new ArrayList<>();
        for (TaskPlan task : tasks) {
            TaskPlan previous = task.getPreviousTask();
            if (previous != null && violated.test(previous, task)) {
                pairs++;
                implicated.add(previous);
                implicated.add(task);
            }
        }
        addRule(target, code, weights, implicated, pairs, pairs + " " + detail);
    }

    private static void addSetup(List<ConstraintJustification> target, List<TaskPlan> tasks,
                                 Map<String, Integer> weights) {
        long minutes = 0;
        List<TaskPlan> violated = new ArrayList<>();
        for (TaskPlan task : tasks) {
            long setup = SoftConstraintPredicates.setupMinutes(task);
            if (setup > 0) {
                minutes += setup;
                violated.add(task);
            }
        }
        addRule(target, ConstraintCodes.MIN_SETUP_TIME, weights, violated, minutes,
                "累计换型 " + minutes + " 分钟");
    }

    private static void addBalance(List<ConstraintJustification> target, List<TaskPlan> tasks,
                                   Map<String, Integer> weights) {
        Map<UUID, List<TaskPlan>> byResource = new LinkedHashMap<>();
        for (TaskPlan task : tasks) {
            if (task.getResourceId() != null) {
                byResource.computeIfAbsent(task.getResourceId(), key -> new ArrayList<>()).add(task);
            }
        }
        long hoursSquared = 0;
        List<TaskPlan> implicated = new ArrayList<>();
        for (List<TaskPlan> resourceTasks : byResource.values()) {
            long loadSeconds = resourceTasks.stream().mapToLong(TaskPlan::getRunSeconds).sum();
            long penalty = SoftConstraintPredicates.loadHoursSquared(loadSeconds);
            if (penalty > 0) {
                hoursSquared += penalty;
                implicated.addAll(resourceTasks);
            }
        }
        addRule(target, ConstraintCodes.BALANCE_LOAD, weights, implicated, hoursSquared,
                "资源负载小时平方和 " + hoursSquared);
    }

    private static void addMakespan(List<ConstraintJustification> target, List<TaskPlan> tasks,
                                    Map<String, Integer> weights) {
        LocalDateTime start = tasks.stream().map(TaskPlan::getStartTime)
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        LocalDateTime end = tasks.stream().map(TaskPlan::getEndTime)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        long minutes = SoftConstraintPredicates.makespanMinutes(start, end);
        addRule(target, ConstraintCodes.MIN_MAKESPAN, weights, tasks, minutes,
                "总完工时间 " + minutes + " 分钟");
    }

    private static void addRule(List<ConstraintJustification> target, String code,
                                Map<String, Integer> weights, List<TaskPlan> implicated,
                                long units, String detail) {
        int weight = weights.getOrDefault(code, 0);
        if (weight <= 0 || units <= 0 || implicated.isEmpty()) {
            return;
        }
        BigDecimal deduction = BigDecimal.valueOf(units).multiply(BigDecimal.valueOf(weight));
        target.add(new ConstraintJustification(
                code,
                "SOFT",
                List.of(),
                detail + "；扣 " + deduction.toPlainString() + " 分（权重 " + weight + "）："
                        + labels(implicated),
                weight,
                deduction));
    }

    private static String labels(List<TaskPlan> tasks) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (TaskPlan task : tasks) {
            labels.add(label(task));
        }
        return String.join("；", labels);
    }

    private static String label(TaskPlan task) {
        return (task.getOrderNo() == null ? "" : task.getOrderNo())
                + "/工序" + task.getOperationIndex();
    }
}
