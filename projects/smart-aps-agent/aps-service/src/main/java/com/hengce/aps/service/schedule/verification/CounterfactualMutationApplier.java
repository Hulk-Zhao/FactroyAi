package com.hengce.aps.service.schedule.verification;

import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.TaskAssignment;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * C52 反事实的“最小改动”应用器。
 *
 * <p>只做两类有真实数据支撑的改动：</p>
 * <ul>
 *   <li>{@code UNFREEZE_TASKS}：把指定 taskId 从 frozenAssignments 移除；</li>
 *   <li>{@code EXTEND_CALENDAR}：按 resourceId + from/to 延长资源可用窗口。</li>
 * </ul>
 *
 * <p>数据不足时不编造：返回带 {@code reason} 的不可用结果，由调用方回 {@code UNVERIFIED}。</p>
 */
public final class CounterfactualMutationApplier {

    private CounterfactualMutationApplier() {
    }

    /**
     * 从冻结分配中移除指定任务。
     *
     * @param problem 原题面
     * @param taskIds 待解冻任务主键
     * @return 改动结果；{@code reason} 非空表示未改动
     */
    public static Mutation unfreeze(ScheduleProblem problem, List<Long> taskIds) {
        Objects.requireNonNull(problem, "problem 不能为空");
        Set<Long> targets = new HashSet<>(taskIds == null ? List.of() : taskIds);
        List<TaskAssignment> kept = new ArrayList<>();
        int removed = 0;
        for (TaskAssignment frozen : problem.frozenAssignments()) {
            if (frozen.taskId() != null && targets.contains(frozen.taskId())) {
                removed++;
            } else {
                kept.add(frozen);
            }
        }
        if (removed == 0) {
            return Mutation.unavailable("taskIds 不在本次题面的冻结分配中，无法解冻（未改动题面）");
        }
        ScheduleProblem mutated = new ScheduleProblem(
                problem.tenantId(), problem.sandboxId(), problem.solveTaskId(), problem.strategyId(),
                problem.direction(), problem.orders(), problem.resources(), List.copyOf(kept),
                problem.constraints(), problem.setupMatrix(), problem.baseTime(),
                problem.initialAssignments());
        return new Mutation(mutated, removed, null, List.of(
                "UNFREEZE_TASKS：从冻结分配移除 " + removed + " 个任务后重解"));
    }

    /**
     * 延长指定资源的最后一个日历窗口。
     *
     * <p>窗口按 start 排序并合并重叠/相邻区间；若请求区间没有为资源新增任何可用时间，
     * 视为“未改动”，避免把无变化的重解说成验证通过。</p>
     *
     * @param problem 原题面
     * @param resourceId 资源主键
     * @param from 新窗口开始（含）
     * @param to 新窗口结束（不含）
     * @return 改动结果
     */
    public static Mutation extendCalendar(ScheduleProblem problem, UUID resourceId,
                                         OffsetDateTime from, OffsetDateTime to) {
        Objects.requireNonNull(problem, "problem 不能为空");
        SchedulingResource target = problem.resources().stream()
                .filter(resource -> resource.resourceId().equals(resourceId))
                .findFirst().orElse(null);
        if (target == null) {
            return Mutation.unavailable("resourceId 不在本次题面中，无法延长日历（未改动题面）");
        }
        if (target.availabilityWindows().isEmpty()) {
            return Mutation.unavailable("该资源为 7×24 全天可用，没有可延长的日历窗口");
        }
        List<AvailabilityWindow> merged = merge(target.availabilityWindows(), from, to);
        long originalSeconds = coveredSeconds(target.availabilityWindows());
        long mergedSeconds = coveredSeconds(merged);
        if (mergedSeconds <= originalSeconds) {
            return Mutation.unavailable("请求窗口没有为资源新增可用时间（已被现有日历覆盖）");
        }
        SchedulingResource extended = new SchedulingResource(target.resourceId(), target.code(),
                target.type(), target.groupId(), target.capacityMode(), target.capacityValue(),
                target.batchCapacity(), target.efficiency(), target.calendarId(), target.status(),
                merged);
        List<SchedulingResource> resources = new ArrayList<>(problem.resources().size());
        for (SchedulingResource resource : problem.resources()) {
            resources.add(resource.resourceId().equals(resourceId) ? extended : resource);
        }
        int affectedTasks = countOperationsOn(problem, resourceId);
        ScheduleProblem mutated = new ScheduleProblem(
                problem.tenantId(), problem.sandboxId(), problem.solveTaskId(), problem.strategyId(),
                problem.direction(), problem.orders(), List.copyOf(resources),
                problem.frozenAssignments(), problem.constraints(), problem.setupMatrix(),
                problem.baseTime(), problem.initialAssignments());
        return new Mutation(mutated, affectedTasks, null, List.of(
                "EXTEND_CALENDAR：资源 " + target.code() + " 日历延长至 " + to + " 后重解"));
    }

    private static List<AvailabilityWindow> merge(List<AvailabilityWindow> windows,
                                                  OffsetDateTime from, OffsetDateTime to) {
        List<AvailabilityWindow> all = new ArrayList<>(windows);
        all.add(new AvailabilityWindow(from, to));
        all.sort(Comparator.comparing(AvailabilityWindow::start));
        List<AvailabilityWindow> merged = new ArrayList<>();
        for (AvailabilityWindow window : all) {
            if (merged.isEmpty()) {
                merged.add(window);
                continue;
            }
            AvailabilityWindow last = merged.get(merged.size() - 1);
            if (!window.start().isAfter(last.end())) {
                OffsetDateTime end = window.end().isAfter(last.end()) ? window.end() : last.end();
                merged.set(merged.size() - 1, new AvailabilityWindow(last.start(), end));
            } else {
                merged.add(window);
            }
        }
        return List.copyOf(merged);
    }

    private static long coveredSeconds(List<AvailabilityWindow> windows) {
        long total = 0L;
        for (AvailabilityWindow window : windows) {
            total += ChronoUnit.SECONDS.between(window.start(), window.end());
        }
        return total;
    }

    private static int countOperationsOn(ScheduleProblem problem, UUID resourceId) {
        int count = 0;
        for (SchedulingOrder order : problem.orders()) {
            for (SchedulingOperation operation : order.operations()) {
                if (resourceId.equals(operation.primaryResourceId())) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 改动结果。
     *
     * @param problem 改动后的题面；不可用时为 null
     * @param affectedTaskCount 受影响工序数；不可用时为 0
     * @param reason 不可用原因；可用时为 null
     * @param notes 进入响应的确定性假设
     */
    public record Mutation(
            ScheduleProblem problem,
            int affectedTaskCount,
            String reason,
            List<String> notes
    ) {

        /**
         * 归一化假设列表。
         */
        public Mutation {
            notes = List.copyOf(notes == null ? List.of() : notes);
        }

        static Mutation unavailable(String reason) {
            return new Mutation(null, 0, reason, List.of());
        }

        /**
         * 是否可继续求解。
         *
         * @return true 表示题面已改动
         */
        public boolean applicable() {
            return problem != null;
        }
    }
}
