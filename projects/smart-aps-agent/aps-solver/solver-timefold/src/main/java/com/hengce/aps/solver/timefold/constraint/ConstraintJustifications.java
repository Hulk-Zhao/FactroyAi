package com.hengce.aps.solver.timefold.constraint;

import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.timefold.dependency.TaskDependency;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于最优解重放硬约束，生成中立 {@link ConstraintJustification} 归因。
 *
 * <p>M1 使用社区版 Timefold，{@code SolutionManager.analyze()} 属商业特性不可用，
 * 因此这里复用 {@link ConstraintPredicates} 与约束流同一套判定口径重放。
 * 新解任务尚无数据库主键，{@link ConstraintJustification#taskIds()} 统一为空列表。</p>
 */
public final class ConstraintJustifications {

    /**
     * 描述中最多枚举的样本数。
     */
    private static final int SAMPLE_LIMIT = 3;

    private ConstraintJustifications() {
    }

    /**
     * 生成约束归因。
     *
     * @param plan 已求解的计划解
     * @return 约束归因列表，无违反映射为空列表
     */
    public static List<ConstraintJustification> from(SchedulePlan plan) {
        List<TaskPlan> tasks = plan == null ? List.of() : plan.getTasks();
        List<ConstraintJustification> justifications = new ArrayList<>();
        addIfAny(justifications, ConstraintCodes.UNASSIGNED_TASK, tasks.stream()
                .filter(ConstraintPredicates::unassigned)
                .map(ConstraintJustifications::label)
                .toList());
        addIfAny(justifications, ConstraintCodes.RESOURCE_NO_OVERLAP, resourceOverlaps(tasks));
        addIfAny(justifications, ConstraintCodes.CALENDAR_AVAILABILITY, calendarViolations(tasks));
        addIfAny(justifications, ConstraintCodes.OPERATION_DEPENDENCY, dependencyViolations(plan));
        addIfAny(justifications, ConstraintCodes.FROZEN_RESOURCE, tasks.stream()
                .filter(ConstraintPredicates::frozenResourceViolated)
                .map(ConstraintJustifications::label)
                .toList());
        addIfAny(justifications, ConstraintCodes.FROZEN_START_TIME, tasks.stream()
                .filter(ConstraintPredicates::frozenStartTimeViolated)
                .map(ConstraintJustifications::label)
                .toList());
        addIfAny(justifications, ConstraintCodes.PRIMARY_RESOURCE_ONLY, tasks.stream()
                .filter(ConstraintPredicates::primaryResourceViolated)
                .map(ConstraintJustifications::label)
                .toList());
        return List.copyOf(justifications);
    }

    private static List<String> resourceOverlaps(List<TaskPlan> tasks) {
        List<String> samples = new ArrayList<>();
        for (int first = 0; first < tasks.size(); first++) {
            for (int second = first + 1; second < tasks.size(); second++) {
                TaskPlan left = tasks.get(first);
                TaskPlan right = tasks.get(second);
                if (ConstraintPredicates.resourceOverlap(left, right)) {
                    samples.add(label(left) + " 与 " + label(right));
                }
            }
        }
        return samples;
    }

    private static List<String> calendarViolations(List<TaskPlan> tasks) {
        return tasks.stream()
                .filter(ConstraintPredicates::calendarViolated)
                .map(ConstraintJustifications::label)
                .toList();
    }

    private static List<String> dependencyViolations(SchedulePlan plan) {
        if (plan == null) {
            return List.of();
        }
        Map<String, TaskPlan> taskByPlanningId = new HashMap<>();
        for (TaskPlan task : plan.getTasks()) {
            taskByPlanningId.put(task.getPlanningId(), task);
        }
        List<String> samples = new ArrayList<>();
        for (TaskDependency dependency : plan.getDependencies()) {
            TaskPlan successor = taskByPlanningId.get(dependency.successorPlanningId());
            TaskPlan predecessor = taskByPlanningId.get(dependency.predecessorPlanningId());
            if (successor == null || predecessor == null) {
                continue;
            }
            if (ConstraintPredicates.dependencyViolated(dependency, successor, predecessor)) {
                samples.add(label(successor) + " " + dependency.dependType()
                        + "(lag=" + dependency.lagMinutes() + "min) 前序 " + label(predecessor));
            }
        }
        return samples;
    }

    private static void addIfAny(
            List<ConstraintJustification> target, String constraintCode, List<String> samples) {
        if (samples.isEmpty()) {
            return;
        }
        String detail = String.join("；", samples.subList(0, Math.min(SAMPLE_LIMIT, samples.size())));
        if (samples.size() > SAMPLE_LIMIT) {
            detail = detail + " 等";
        }
        target.add(new ConstraintJustification(
                constraintCode, "HARD", List.of(), samples.size() + " 处违反: " + detail));
    }

    private static String label(TaskPlan task) {
        return task.getOrderNo() + "/工序" + task.getOperationIndex();
    }
}
