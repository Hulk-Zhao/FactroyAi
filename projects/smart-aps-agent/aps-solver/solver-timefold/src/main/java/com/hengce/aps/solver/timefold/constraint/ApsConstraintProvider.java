package com.hengce.aps.solver.timefold.constraint;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import com.hengce.aps.solver.timefold.dependency.TaskDependency;
import com.hengce.aps.solver.timefold.model.TaskPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * 硬约束集（M1）与 8 条核心软约束（M2）：
 *
 * <ul>
 *   <li>资源不重叠：主资源占用 {@code [setupStart, end)}，辅助资源占用加工窗口 {@code [start, end)}（C12 §17.4）；</li>
 *   <li>工作日历：任务占用块必须完整落在资源的一个可用时段内（C12 §17.1）；</li>
 *   <li>工序依赖：按 FS/SS/FF 与滞后判定（C12 §17.2）；</li>
 *   <li>冻结任务资源与开始时间不可移动；任务只能排在其主资源上；</li>
 *   <li>C21 锁定：{@code TIME_WINDOW} 闭区间时间窗、{@code RESOURCE} 主资源白名单，
 *       均为硬约束；{@code lockType} 为空或 {@code NONE} 时不惩罚；</li>
 *   <li>软约束：最小超期、交期优先、短任务优先、同品连续、最小换型、均衡负载、优先级尊重、最小总完工时间，
 *       权重来自 {@link SoftConstraintWeights}，默认关闭以保证 M1 仅硬约束行为不变。</li>
 * </ul>
 */
public class ApsConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        List<Constraint> constraints = new ArrayList<>(List.of(
                unassignedTask(factory),
                resourceNoOverlap(factory),
                calendarAvailability(factory),
                operationDependency(factory),
                orderNotBefore(factory),
                frozenResource(factory),
                frozenStartTime(factory),
                primaryResourceOnly(factory),
                timeWindowLock(factory),
                resourceLock(factory)));
        if (SoftConstraintWeights.isEnabled()) {
            addSoftConstraint(constraints, ConstraintCodes.MIN_TARDINESS, minTardiness(factory));
            addSoftConstraint(constraints, ConstraintCodes.EDD, edd(factory));
            addSoftConstraint(constraints, ConstraintCodes.SPT, spt(factory));
            addSoftConstraint(constraints, ConstraintCodes.SAME_PRODUCT_CONTINUOUS,
                    sameProductContinuous(factory));
            addSoftConstraint(constraints, ConstraintCodes.MIN_SETUP_TIME, minSetupTime(factory));
            addSoftConstraint(constraints, ConstraintCodes.BALANCE_LOAD, balanceLoad(factory));
            addSoftConstraint(constraints, ConstraintCodes.PRIORITY_RESPECT, priorityRespect(factory));
            addSoftConstraint(constraints, ConstraintCodes.MIN_MAKESPAN, minMakespan(factory));
        }
        return constraints.toArray(Constraint[]::new);
    }

    private void addSoftConstraint(List<Constraint> constraints, String code, Constraint constraint) {
        if (SoftConstraintWeights.weightOf(code) > 0) {
            constraints.add(constraint);
        }
    }

    /**
     * 未排位任务硬惩罚：每条未进入资源序列 / 缺开始时间的任务罚 1 hard。
     *
     * <p>{@code forEach} 只遍历“全部规划变量已初始化”的实体，初始解与构造启发式
     * 超时返回的部分解中所有任务都会被静默跳过，得分因此恒为 {@code 0hard/0soft}。
     * 必须用 {@code forEachIncludingUnassigned} 才能让“2101/3000”这类部分解不可行。</p>
     *
     * @param factory 约束工厂
     * @return 约束
     */
    private Constraint unassignedTask(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(TaskPlan.class)
                .filter(ConstraintPredicates::unassigned)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint(ConstraintCodes.UNASSIGNED_TASK);
    }

    /**
     * 资源不重叠：先按 {@code resourceId} 把“任务—资源占用切片”扁平化分组，再在组内配对。
     *
     * <p>语义与全表 {@code forEachUniquePair} 完全一致：主资源按 {@code [startTime, endTime)}
     * （含换型）、辅助资源按 {@code [processingStart, endTime)}（仅加工窗口），同资源重叠
     * 每对罚 1 hard；不同资源不会进入同一分组，因此不会互相配对。区别仅在配对规模：
     * 由 n² 降为 Σkᵢ²（kᵢ 为单资源上的占用切片数），且增量评分只重算受影响资源组。</p>
     *
     * @param factory 约束工厂
     * @return 约束
     */
    private Constraint resourceNoOverlap(ConstraintFactory factory) {
        return factory.forEach(TaskPlan.class)
                .flattenLast(TaskPlan::occupancySlots)
                .groupBy(TaskPlan.OccupancySlot::resourceId, ConstraintCollectors.toList())
                .penalize(HardSoftScore.ONE_HARD,
                        (resourceId, slots) -> ConstraintPredicates.overlapPairCount(slots))
                .asConstraint(ConstraintCodes.RESOURCE_NO_OVERLAP);
    }

    private Constraint calendarAvailability(ConstraintFactory factory) {
        return factory.forEach(TaskPlan.class)
                .filter(ConstraintPredicates::calendarViolated)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint(ConstraintCodes.CALENDAR_AVAILABILITY);
    }

    private Constraint operationDependency(ConstraintFactory factory) {
        return factory.forEach(TaskDependency.class)
                .join(TaskPlan.class, Joiners.equal(
                        TaskDependency::successorPlanningId, TaskPlan::getPlanningId))
                .join(TaskPlan.class, Joiners.equal(
                        (TaskDependency dependency, TaskPlan successor) ->
                                dependency.predecessorPlanningId(),
                        TaskPlan::getPlanningId))
                .filter(ConstraintPredicates::dependencyViolated)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint(ConstraintCodes.OPERATION_DEPENDENCY);
    }

    /**
     * 跨窗口下界（硬约束）：订单窗口内工序不得早于该订单窗口外 FROZEN 任务的最大结束时间。
     *
     * <p>{@code findFrozenTasks} 按求解窗口过滤后，落在窗口起点之前的 FROZEN 前序不在题面里，
     * 工序依赖边自然也不存在；若再不给订单级下界，求解器会把后续工序排到已完工工序之前，
     * 得到“0hard 但现实不可执行”的计划。下界由装配侧按订单计算并写入任务。</p>
     *
     * @param factory 约束工厂
     * @return 约束
     */
    private Constraint orderNotBefore(ConstraintFactory factory) {
        return factory.forEach(TaskPlan.class)
                .filter(ConstraintPredicates::orderNotBeforeViolated)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint(ConstraintCodes.ORDER_NOT_BEFORE);
    }

    private Constraint frozenResource(ConstraintFactory factory) {
        return factory.forEach(TaskPlan.class)
                .filter(ConstraintPredicates::frozenResourceViolated)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint(ConstraintCodes.FROZEN_RESOURCE);
    }

    private Constraint frozenStartTime(ConstraintFactory factory) {
        return factory.forEach(TaskPlan.class)
                .filter(ConstraintPredicates::frozenStartTimeViolated)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint(ConstraintCodes.FROZEN_START_TIME);
    }

    private Constraint primaryResourceOnly(ConstraintFactory factory) {
        return factory.forEach(TaskPlan.class)
                .filter(ConstraintPredicates::primaryResourceViolated)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint(ConstraintCodes.PRIMARY_RESOURCE_ONLY);
    }

    /**
     * C21 时间窗锁（硬约束，闭区间 {@code [lockedFrom, lockedTo]}）。
     *
     * @param factory 约束工厂
     * @return 约束
     */
    private Constraint timeWindowLock(ConstraintFactory factory) {
        return factory.forEach(TaskPlan.class)
                .filter(ConstraintPredicates::timeWindowLockViolated)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint(ConstraintCodes.TIME_WINDOW_LOCK);
    }

    /**
     * C21 资源锁（硬约束，主资源必须属于 {@code lockedResourceIds}）。
     *
     * @param factory 约束工厂
     * @return 约束
     */
    private Constraint resourceLock(ConstraintFactory factory) {
        return factory.forEach(TaskPlan.class)
                .filter(ConstraintPredicates::resourceLockViolated)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint(ConstraintCodes.RESOURCE_LOCK);
    }

    private Constraint minTardiness(ConstraintFactory factory) {
        int weight = SoftConstraintWeights.weightOf(ConstraintCodes.MIN_TARDINESS);
        return factory.forEach(TaskPlan.class)
                .filter(task -> task.getStartTime() != null && SoftConstraintPredicates.tardinessMinutes(task) > 0)
                .penalize(HardSoftScore.ofSoft(weight), SoftConstraintPredicates::tardinessMinutes)
                .asConstraint(ConstraintCodes.MIN_TARDINESS);
    }

    private Constraint edd(ConstraintFactory factory) {
        int weight = SoftConstraintWeights.weightOf(ConstraintCodes.EDD);
        return factory.forEach(TaskPlan.class)
                .filter(task -> task.getPreviousTask() != null
                        && SoftConstraintPredicates.dueOrderViolated(task.getPreviousTask(), task))
                .penalize(HardSoftScore.ofSoft(weight))
                .asConstraint(ConstraintCodes.EDD);
    }

    private Constraint spt(ConstraintFactory factory) {
        int weight = SoftConstraintWeights.weightOf(ConstraintCodes.SPT);
        return factory.forEach(TaskPlan.class)
                .filter(task -> task.getPreviousTask() != null
                        && SoftConstraintPredicates.sptViolated(task.getPreviousTask(), task))
                .penalize(HardSoftScore.ofSoft(weight))
                .asConstraint(ConstraintCodes.SPT);
    }

    private Constraint sameProductContinuous(ConstraintFactory factory) {
        int weight = SoftConstraintWeights.weightOf(ConstraintCodes.SAME_PRODUCT_CONTINUOUS);
        return factory.forEach(TaskPlan.class)
                .filter(task -> task.getPreviousTask() != null
                        && SoftConstraintPredicates.familyChangeViolated(task.getPreviousTask(), task))
                .penalize(HardSoftScore.ofSoft(weight))
                .asConstraint(ConstraintCodes.SAME_PRODUCT_CONTINUOUS);
    }

    private Constraint minSetupTime(ConstraintFactory factory) {
        int weight = SoftConstraintWeights.weightOf(ConstraintCodes.MIN_SETUP_TIME);
        return factory.forEach(TaskPlan.class)
                .filter(task -> task.getStartTime() != null && SoftConstraintPredicates.setupMinutes(task) > 0)
                .penalize(HardSoftScore.ofSoft(weight), SoftConstraintPredicates::setupMinutes)
                .asConstraint(ConstraintCodes.MIN_SETUP_TIME);
    }

    private Constraint balanceLoad(ConstraintFactory factory) {
        int weight = SoftConstraintWeights.weightOf(ConstraintCodes.BALANCE_LOAD);
        return factory.forEach(TaskPlan.class)
                .filter(task -> task.getResourceId() != null && task.getStartTime() != null)
                .groupBy(TaskPlan::getResourceId, ConstraintCollectors.sum(TaskPlan::getRunSeconds))
                .penalize(HardSoftScore.ofSoft(weight),
                        (resourceId, loadSeconds) -> SoftConstraintPredicates.loadHoursSquared(loadSeconds))
                .asConstraint(ConstraintCodes.BALANCE_LOAD);
    }

    private Constraint priorityRespect(ConstraintFactory factory) {
        int weight = SoftConstraintWeights.weightOf(ConstraintCodes.PRIORITY_RESPECT);
        return factory.forEach(TaskPlan.class)
                .filter(task -> task.getPreviousTask() != null
                        && SoftConstraintPredicates.priorityViolated(task.getPreviousTask(), task))
                .penalize(HardSoftScore.ofSoft(weight))
                .asConstraint(ConstraintCodes.PRIORITY_RESPECT);
    }

    private Constraint minMakespan(ConstraintFactory factory) {
        int weight = SoftConstraintWeights.weightOf(ConstraintCodes.MIN_MAKESPAN);
        return factory.forEach(TaskPlan.class)
                .filter(task -> task.getStartTime() != null)
                .groupBy(ConstraintCollectors.min(TaskPlan::getStartTime),
                        ConstraintCollectors.max(TaskPlan::getEndTime))
                .penalize(HardSoftScore.ofSoft(weight),
                        (start, end) -> SoftConstraintPredicates.makespanMinutes(start, end))
                .asConstraint(ConstraintCodes.MIN_MAKESPAN);
    }
}
