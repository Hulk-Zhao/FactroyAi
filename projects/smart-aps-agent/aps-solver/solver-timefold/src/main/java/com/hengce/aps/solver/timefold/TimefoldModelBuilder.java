package com.hengce.aps.solver.timefold;

import ai.timefold.solver.core.api.score.HardSoftScore;
import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.timefold.calendar.ResourceAvailability;
import com.hengce.aps.solver.timefold.calendar.TimeWindow;
import com.hengce.aps.solver.timefold.dependency.DependencyGraph;
import com.hengce.aps.solver.timefold.dependency.DependencyGraphBuilder;
import com.hengce.aps.solver.timefold.duration.DurationCalculator;
import com.hengce.aps.solver.timefold.duration.TaskDuration;
import com.hengce.aps.solver.timefold.model.ResourcePlan;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 中立 {@link ScheduleProblem} 与 Timefold {@link SchedulePlan} 之间的转换。
 *
 * <p>C12 落地：可用时段（§17.1）、工序依赖（§17.2，SF/负滞后/环直接拒绝）、
 * 换型矩阵（§17.3，未命中回退静态换型）、辅助资源占用（§17.4）。
 * 三项降级标记写入计划解并随结果进入 {@code SolveMetrics.extra}。</p>
 */
public final class TimefoldModelBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimefoldModelBuilder.class);

    /**
     * 开始时间候选的粒度（小时）。
     */
    private static final int START_TIME_STEP_HOURS = 1;

    /**
     * 开始时间候选覆盖的 horizon（小时，7 天）。
     */
    private static final int HORIZON_HOURS = 168;

    private TimefoldModelBuilder() {
    }

    /**
     * 构建 Timefold 计划解。
     *
     * @param problem 中立排产问题
     * @return 计划解
     */
    public static SchedulePlan build(ScheduleProblem problem) {
        Map<UUID, SchedulingResource> resourceById = new LinkedHashMap<>();
        for (SchedulingResource resource : problem.resources()) {
            if (resource.resourceId() == null) {
                throw new IllegalArgumentException("资源缺少 resourceId: " + resource.code());
            }
            resourceById.put(resource.resourceId(), resource);
        }
        if (resourceById.isEmpty()) {
            throw new IllegalArgumentException("排产问题缺少资源");
        }

        Map<UUID, List<TimeWindow>> windowsByResource = buildAvailabilityWindows(resourceById);
        boolean calendarFallback = resourceById.values().stream()
                .anyMatch(resource -> resource.availabilityWindows().isEmpty());

        Map<UUID, ResourcePlan> resourcePlans = new LinkedHashMap<>();
        for (SchedulingResource resource : resourceById.values()) {
            resourcePlans.put(resource.resourceId(), new ResourcePlan(resource));
        }

        Map<String, TaskAssignment> frozenByKey = new LinkedHashMap<>();
        for (TaskAssignment frozen : problem.frozenAssignments()) {
            frozenByKey.put(taskKey(frozen.orderId(), frozen.operationIndex()), frozen);
        }
        Map<String, TaskAssignment> initialByKey = new LinkedHashMap<>();
        for (TaskAssignment initial : problem.initialAssignments()) {
            initialByKey.put(taskKey(initial.orderId(), initial.operationIndex()), initial);
        }

        LocalDateTime base = problem.baseTime() == null ? currentBaseTime() : problem.baseTime();
        int initializedTaskCount = 0;
        List<TaskPlan> tasks = new ArrayList<>();
        for (SchedulingOrder order : problem.orders()) {
            List<SchedulingOperation> operations = order.operations().stream()
                    .sorted(Comparator.comparingInt(SchedulingOperation::operationIndex))
                    .toList();
            for (SchedulingOperation operation : operations) {
                TaskAssignment frozen = frozenByKey.get(taskKey(order.orderId(), operation.operationIndex()));
                SchedulingResource resource = resolveResource(operation, resourceById, frozen);
                TaskDuration duration = DurationCalculator.calculate(resource, operation, order.qty());
                TaskPlan task = createTask(order, operation, duration, frozen);
                task.setSetupMatrixEntries(problem.setupMatrix());
                task.setAvailabilityRequirements(availabilityRequirements(
                        operation, resource, resourceById, windowsByResource));
                tasks.add(task);
                placeFrozenTask(task, frozen, resourcePlans);
                if (task.isPinned() || placeInitialTask(task, initialByKey.get(
                        taskKey(order.orderId(), operation.operationIndex())), resourcePlans)) {
                    initializedTaskCount++;
                }
            }
        }

        if (!initialByKey.isEmpty()) {
            // 预热：按既有开始时间排序资源列表，使 previousTask 与实际相邻前序一致，
            // 还原既有计划的换型工时；否则列表顺序随机会让换型值变化，凭空产生硬违约。
            for (ResourcePlan resourcePlan : resourcePlans.values()) {
                resourcePlan.getTasks().sort(Comparator.comparing(
                        TaskPlan::getStartTime, Comparator.nullsLast(Comparator.naturalOrder())));
            }
        }

        DependencyGraph dependencyGraph = DependencyGraphBuilder.build(problem.orders());
        // 只有“所有资源都配置了可用时段”时，候选开始时间才能按窗口并集裁剪；
        // 任一资源无日历时它被视为全天可用，裁剪会误删该资源的合法开始时间。
        boolean fullCalendar = resourceById.keySet().stream().allMatch(resourceId -> {
            List<TimeWindow> windows = windowsByResource.get(resourceId);
            return windows != null && !windows.isEmpty();
        });
        List<LocalDateTime> startTimes = buildStartTimeRange(
                tasks, base, fullCalendar ? windowsByResource : Map.of());
        SchedulePlan plan = new SchedulePlan(
                startTimes, new ArrayList<>(resourcePlans.values()), tasks, dependencyGraph.edges());
        // 只有“全部任务都拿到了既有排产结果”才能跳过构造启发式；否则初始解不完整，
        // 必须退回冷启动（构造启发式会重新排满）。
        plan.setWarmStarted(!tasks.isEmpty() && initializedTaskCount == tasks.size());
        plan.setCalendarFallback(calendarFallback);
        plan.setDependencyFallback(dependencyGraph.fallback());
        plan.setSetupMatrixFallback(problem.setupMatrix().isEmpty());
        return plan;
    }

    /**
     * 将 Timefold 最优解映射为中立结果。
     *
     * <p><b>状态口径</b>：只有“所有任务均已排位且 hard 分为 0”才是 {@code SOLVED}；
     * 存在未排位任务且已耗尽时限时返回 {@code TIMEOUT}（构造启发式未完成，最优解只是
     * 部分解/初始解，必须丢弃、不得落库）；其余不可行情况返回 {@code INFEASIBLE}。</p>
     *
     * @param best 最优解
     * @param elapsedMs 求解耗时（毫秒）
     * @param justifications 约束归因
     * @param terminationReason 终止原因（TIME_LIMIT / TERMINATED）
     * @param timeLimitReached 是否因到达时限而终止
     * @return 中立结果
     */
    public static ScheduleSolution toSolution(
            SchedulePlan best,
            long elapsedMs,
            List<ConstraintJustification> justifications,
            String terminationReason,
            boolean timeLimitReached) {
        return toSolution(best, elapsedMs, justifications, terminationReason, timeLimitReached,
                Map.of());
    }

    /**
     * 同上，并合并引擎级指标（例如移动线程的“请求值/生效值/是否被忽略”）。
     *
     * @param best 最优解
     * @param elapsedMs 求解耗时（毫秒）
     * @param justifications 约束归因
     * @param terminationReason 终止原因（TIME_LIMIT / TERMINATED）
     * @param timeLimitReached 是否因到达时限而终止
     * @param solverExtra 引擎级指标片段
     * @return 中立结果
     */
    public static ScheduleSolution toSolution(
            SchedulePlan best,
            long elapsedMs,
            List<ConstraintJustification> justifications,
            String terminationReason,
            boolean timeLimitReached,
            Map<String, Object> solverExtra) {
        List<TaskAssignment> assignments = new ArrayList<>();
        for (ResourcePlan resource : best.getResources()) {
            List<TaskPlan> orderedTasks = new ArrayList<>(resource.getTasks());
            orderedTasks.sort(Comparator.comparing(
                    TaskPlan::getStartTime, Comparator.nullsLast(Comparator.naturalOrder())));
            for (int index = 0; index < orderedTasks.size(); index++) {
                TaskPlan task = orderedTasks.get(index);
                if (task.getStartTime() != null) {
                    assignments.add(toAssignment(resource, task, index));
                }
            }
        }
        HardSoftScore score = best.getScore() == null ? HardSoftScore.ZERO : best.getScore();
        int frozenStartOutsideCalendar = frozenStartOutsideCalendarCount(best.getTasks());
        int unassignedTaskCount = (int) best.getTasks().stream()
                .filter(task -> task.getResourceId() == null || task.getStartTime() == null)
                .count();
        boolean usable = unassignedTaskCount == 0 && score.isFeasible();
        String status;
        if (usable) {
            // 时限到达但结果可用（覆盖完整 + hard=0）时仍判 SOLVED，不因“超时”抹掉合法结果。
            status = "SOLVED";
        } else if (unassignedTaskCount == 0 && frozenStartOutsideCalendar > 0) {
            // 任务已排满且冻结起点非法：任何重排都无法消除该硬违约，应明确判为不可行，
            // 而不是因为时限到达被改判 TIMEOUT（TIMEOUT 语义是“构造未完成、部分解丢弃”）。
            status = "INFEASIBLE";
        } else if (timeLimitReached) {
            // 时限到达且结果不可用：一律 TIMEOUT，不得用 ENGINE_ERROR 兜时间上限。
            status = "TIMEOUT";
        } else {
            status = "INFEASIBLE";
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("engine", "timefold");
        extra.put("taskCount", best.getTasks().size());
        extra.put("assignedTaskCount", assignments.size());
        extra.put("unassignedTaskCount", unassignedTaskCount);
        extra.put("discardedBestSolution", status.equals("TIMEOUT"));
        extra.put("calendarFallback", best.isCalendarFallback());
        extra.put("dependencyFallback", best.isDependencyFallback());
        extra.put("setupMatrixFallback", best.isSetupMatrixFallback());
        // 冻结任务固定起点非法时不做静默合法化：违约保留在结果里，并在此显式计数。
        extra.put("frozenStartOutsideCalendarCount", frozenStartOutsideCalendar);
        extra.putAll(solverExtra);
        return new ScheduleSolution(
                status,
                new ScoreFact(
                        BigDecimal.valueOf(score.hardScore()),
                        BigDecimal.valueOf(score.softScore()),
                        score.toString()),
                assignments,
                justifications,
                new SolveMetrics(
                        elapsedMs,
                        terminationReason,
                        List.of(),
                        assignments.size(),
                        extra),
                message(score, best, status, unassignedTaskCount));
    }

    private static Map<UUID, List<TimeWindow>> buildAvailabilityWindows(
            Map<UUID, SchedulingResource> resourceById) {
        Map<UUID, List<TimeWindow>> windowsByResource = new LinkedHashMap<>();
        for (SchedulingResource resource : resourceById.values()) {
            List<TimeWindow> windows = new ArrayList<>();
            for (AvailabilityWindow window : resource.availabilityWindows()) {
                LocalDateTime start = toLocal(window.start());
                LocalDateTime end = toLocal(window.end());
                if (!windows.isEmpty() && !start.isAfter(windows.get(windows.size() - 1).start())) {
                    throw new IllegalArgumentException(
                            "资源可用时段必须按 start 升序且不重叠: " + resource.code());
                }
                if (!windows.isEmpty() && start.isBefore(windows.get(windows.size() - 1).end())) {
                    throw new IllegalArgumentException(
                            "资源可用时段必须按 start 升序且不重叠: " + resource.code());
                }
                windows.add(new TimeWindow(start, end));
            }
            windowsByResource.put(resource.resourceId(), List.copyOf(windows));
        }
        return windowsByResource;
    }

    private static List<ResourceAvailability> availabilityRequirements(
            SchedulingOperation operation,
            SchedulingResource primaryResource,
            Map<UUID, SchedulingResource> resourceById,
            Map<UUID, List<TimeWindow>> windowsByResource) {
        List<ResourceAvailability> requirements = new ArrayList<>();
        addRequirement(requirements, primaryResource, windowsByResource);
        for (UUID secondaryResourceId : operation.secondaryResourceIds()) {
            SchedulingResource secondaryResource = resourceById.get(secondaryResourceId);
            if (secondaryResource == null) {
                throw new IllegalArgumentException("工序的辅助资源不存在: "
                        + operation.operationCode() + " / " + secondaryResourceId);
            }
            addRequirement(requirements, secondaryResource, windowsByResource);
        }
        return List.copyOf(requirements);
    }

    private static void addRequirement(
            List<ResourceAvailability> requirements,
            SchedulingResource resource,
            Map<UUID, List<TimeWindow>> windowsByResource) {
        List<TimeWindow> windows = windowsByResource.get(resource.resourceId());
        if (windows == null || windows.isEmpty()) {
            return;
        }
        requirements.add(new ResourceAvailability(resource.resourceId(), resource.code(), windows));
    }

    private static TaskPlan createTask(
            SchedulingOrder order,
            SchedulingOperation operation,
            TaskDuration duration,
            TaskAssignment existing) {
        boolean frozen = isFrozenLevel(existing);
        boolean pinned = existing != null && existing.frozen()
                && existing.startTime() != null && existing.resourceId() != null;
        boolean placeAtExisting = frozen || pinned;
        TaskPlan task = new TaskPlan(
                order.orderId(),
                order.orderNo(),
                operation.operationIndex(),
                operation.operationName(),
                operation.operationTemplateId(),
                operation.primaryResourceId(),
                operation.secondaryResourceIds(),
                order.qty(),
                duration.setupSeconds(),
                duration.runSeconds(),
                frozen,
                placeAtExisting && existing != null ? existing.resourceId() : null,
                placeAtExisting && existing != null ? frozenStartTime(existing) : null);
        task.setPinned(pinned);
        if (existing != null) {
            task.setLockType(existing.lockType());
            task.setLockedFrom(toLocal(existing.lockedFrom()));
            task.setLockedTo(toLocal(existing.lockedTo()));
            task.setLockedResourceIds(existing.lockedResourceIds());
        }
        task.setOperationCode(operation.operationCode());
        task.setMaterialCode(order.materialCode());
        task.setOrderNotBefore(order.notBefore());
        applySoftAttributes(task, order);
        return task;
    }

    /**
     * 把既有排产结果作为预热初始解放入资源序列。
     *
     * <p>只要资源在本次题面内就放入初始解，即使时间落在 horizon 外：这些任务作为
     * “未改进的起点”，由局部搜索把它们移回窗口内并修掉日历违约。冻结/固定任务
     * 已由 {@link #placeFrozenTask} 放置，这里直接跳过。</p>
     *
     * @param task 计划任务
     * @param initial 既有分配，可空
     * @param resourcePlans 资源计划
     * @return {@code true} 表示已作为初始解放置
     */
    private static boolean placeInitialTask(
            TaskPlan task,
            TaskAssignment initial,
            Map<UUID, ResourcePlan> resourcePlans) {
        if (initial == null || task.isPinned() || task.isFrozen()) {
            return false;
        }
        LocalDateTime start = toLocal(initial.startTime());
        if (start == null) {
            return false;
        }
        ResourcePlan resourcePlan = resourcePlans.get(initial.resourceId());
        if (resourcePlan == null) {
            return false;
        }
        task.setStartTime(start);
        resourcePlan.getTasks().add(task);
        return true;
    }

    /**
     * FROZEN 层判定（C21：{@code FROZEN > lockType > frozen:boolean}）。
     *
     * <p>只有任务状态为 {@code FROZEN} 或 {@code lockType=FROZEN} 时才把既有分配
     * 作为 pinned 起止/资源；{@code TIME_WINDOW}/{@code RESOURCE} 只写入锁字段，
     * 任务仍然可移动，由 W2 的锁硬约束限制其可行域。</p>
     *
     * @param existing 既有分配，可空
     * @return {@code true} 表示 FROZEN 层
     */
    private static boolean isFrozenLevel(TaskAssignment existing) {
        if (existing == null) {
            return false;
        }
        return "FROZEN".equals(existing.status())
                || "FROZEN".equals(existing.lockType());
    }

    /**
     * 映射软约束所需订单字段到计划任务（第 2 节 W4 对 W2 的 D3 前置）：
     * 交期用于 MIN_TARDINESS / EDD，优先级用于 PRIORITY_RESPECT，
     * 产品族用于 SAME_PRODUCT_CONTINUOUS。
     *
     * <p>缺字段 → 规则静默不生效（不会报错，只会让该规则恒为 0 分）：</p>
     * <pre>
     * | 规则编码               | 依赖字段       | 字段缺失时的行为                     |
     * |------------------------|----------------|--------------------------------------|
     * | MIN_TARDINESS          | dueDate        | 逾期谓词返回 null，该任务不计逾期分   |
     * | PRIORITY_RESPECT       | priority       | 优先级排序谓词失效，不产生该规则得分  |
     * | SAME_PRODUCT_CONTINUOUS| productFamily  | 同族判定失效，换型/连续同族得分不产生  |
     * | MIN_SETUP_TIME         | productFamily  | 换型时间按 0 计，规则静默失效         |
     * | MIN_MAKESPAN           | 无             | 仅依赖任务时间，字段缺失不受影响      |
     * | BALANCE_LOAD           | 无             | 仅依赖资源负载，字段缺失不受影响      |
     * | MIN_SOFT_MAKESPAN      | 无             | 仅依赖任务时间，字段缺失不受影响      |
     * | EDD                    | dueDate        | 交期缺失时按无交期任务处理，排序退化  |
     * </pre>
     *
     * <p>因此 M2 必须先完成 `orders.due_date/priority/product_family` 的采集；
     * 缺失时软约束不会失败、但也不会生效，验收数字会“看起来正常却毫无约束力”。</p>
     *
     * @param task 计划任务
     * @param order 订单快照
     */
    private static void applySoftAttributes(TaskPlan task, SchedulingOrder order) {
        task.setDueDate(order.dueDate());
        task.setPriority(order.priority());
        task.setProductFamily(order.productFamily());
    }

    private static void placeFrozenTask(
            TaskPlan task,
            TaskAssignment frozen,
            Map<UUID, ResourcePlan> resourcePlans) {
        if (frozen == null || task.getPinnedStartTime() == null) {
            return;
        }
        task.setStartTime(task.getPinnedStartTime());
        ResourcePlan resourcePlan = resourcePlans.get(task.getPinnedResourceId());
        if (resourcePlan != null) {
            resourcePlan.getTasks().add(task);
        }
        if (!allowedAt(task, task.getPinnedStartTime())) {
            // 确定性策略：冻结起点原样保留（不移动、不丢弃、不静默合法化）。它不可移动，
            // 日历硬违约无法通过重排消除；我们让 calendarAvailability 如实扣分，并由
            // metrics.frozenStartOutsideCalendarCount、WARN 日志与结果 message 标记不可行。
            LOGGER.warn("冻结任务起点不在可用窗口内，按固定解保留: orderNo={} operationIndex={} "
                            + "start={} resourceId={}",
                    task.getOrderNo(), task.getOperationIndex(),
                    task.getPinnedStartTime(), task.getPinnedResourceId());
        }
    }

    /**
     * 统计“有固定起点且最终起点仍不满足可用时段”的任务数。
     *
     * <p>用求解后的实际开始时间判定，因此换型矩阵导致的实际换型变长也能被计入
     * （候选裁剪本身仍按静态换型近似）。</p>
     *
     * @param tasks 计划任务
     * @return 数量；{@code 0} 表示没有冻结任务落在日历外
     */
    static int frozenStartOutsideCalendarCount(List<TaskPlan> tasks) {
        int count = 0;
        for (TaskPlan task : tasks) {
            if (task.getPinnedStartTime() != null
                    && task.getStartTime() != null
                    && !task.isWithinAvailability()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 解析工序的标称资源（C15 §20.3.1.4 冻结口径）。
     *
     * <p>标称资源只用于在建模型时算一次 {@code runSeconds}，资源归属仍由
     * {@code ResourcePlan} 的 list variable 决定：</p>
     * <ol>
     *   <li>固定主资源（{@code FIXED}）→ 该资源；</li>
     *   <li>浮动（{@code FLOAT}）+ {@code RESOURCE} 锁 → {@code lockedResourceIds}
     *       中 {@code resource.code} 最小者；</li>
     *   <li>浮动 + 无资源锁 → 场景内全部启用资源中 {@code resource.code} 最小者
     *       （装配层已按 code 升序，顺序确定）。</li>
     * </ol>
     *
     * <p>三种产能模式都依赖资源（{@code UNIT_PER_HOUR} 用 capacity_value × efficiency、
     * {@code BATCH_SECONDS} 用 capacity_value/batch_capacity、{@code SECONDS_PER_UNIT}
     * 也要除以 efficiency），因此候选资源间时长必须等价；该等价性由装配阶段按
     * 同一口径前置校验（不一致返回 42200），本方法只在等价的前提下取标称资源。</p>
     *
     * @param operation 工序快照
     * @param resourceById 场景内资源
     * @param existing 既有分配，可空（提供任务级 {@code RESOURCE} 锁）
     * @return 标称资源
     */
    private static SchedulingResource resolveResource(
            SchedulingOperation operation,
            Map<UUID, SchedulingResource> resourceById,
            TaskAssignment existing) {
        UUID primaryResourceId = operation.primaryResourceId();
        if (primaryResourceId != null) {
            SchedulingResource resource = resourceById.get(primaryResourceId);
            if (resource == null) {
                throw new IllegalArgumentException(
                        "工序的主资源不存在: " + operation.operationCode() + " / " + primaryResourceId);
            }
            return resource;
        }
        if (existing != null && "RESOURCE".equals(existing.lockType())
                && existing.lockedResourceIds() != null && !existing.lockedResourceIds().isEmpty()) {
            List<SchedulingResource> locked = existing.lockedResourceIds().stream()
                    .map(resourceById::get)
                    .filter(resource -> resource != null)
                    .toList();
            if (!locked.isEmpty()) {
                return minByCode(locked);
            }
        }
        return minByCode(new ArrayList<>(resourceById.values()));
    }

    /**
     * 按 {@code resource.code} 字典序取最小资源。
     *
     * @param resources 候选资源
     * @return 标称资源
     */
    private static SchedulingResource minByCode(List<SchedulingResource> resources) {
        return resources.stream()
                .min(Comparator.comparing(SchedulingResource::code,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseThrow(() -> new IllegalArgumentException("排产问题缺少可用资源"));
    }

    /**
     * 当前时刻向下取整到小时（UTC）；仅在题面未指定基准时间时使用。
     *
     * @return 基准时间
     */
    static LocalDateTime currentBaseTime() {
        return LocalDateTime.now(ZoneOffset.UTC)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
    }

    /**
     * 构建开始时间候选集。
     *
     * <p>传入资源可用窗口时，只保留“{@code start + 任务占用时长} 完整落在可用窗口内”的整点：
     * 任务占用块为 {@code [start, start + 静态换型 + 加工时长)}，必须同时满足主资源与辅助资源的
     * 可用时段要求（口径与 {@code ResourceAvailability.allows} 一致）。只判断 {@code start}
     * 是否落在窗口会漏掉“任务尾部跨过窗口终点”的候选，导致 2 小时任务在 8 小时班次末尾开始、
     * 必然触发 {@code calendarAvailability} 硬违约。</p>
     *
     * <p>裁剪只剔除“对任何可移动任务都不可用”的整点：任一任务原本可用的候选都会被保留，
     * 因此不会把本来可行的计划裁成不可行。</p>
     *
     * <p><b>已赋值起点策略（冻结/预热）</b>：pinned/预热起点一律补入值域。Timefold 在初始化时
     * 会校验“已赋值必须属于值域”，若为了合法性把非法冻结起点排除出值域，求解会直接
     * {@code FAILED}（回归用例 {@code frozenStartOutsideCalendarIsKeptFixedAndReported} 已固化）。
     * 因此冻结任务的非法起点<b>原样保留</b>：它不可移动（{@code @PlanningPin} 与
     * {@code frozenStartTime} 硬约束），我们既不移动也不丢弃，而是让它以
     * {@code calendarAvailability} 硬违约显式暴露，并通过 {@code metrics.extra} 的
     * {@code frozenStartOutsideCalendarCount}、构建期 WARN 与结果 message 如实标记不可行。
     * 非冻结的预热任务仍可移动，非法预热起点交由局部搜索修回窗口；修不掉同样以硬违约可见，
     * 不会静默当作合法。</p>
     *
     * @param tasks 计划任务
     * @param base 基准时间（UTC 整点）
     * @param windowsByResource 资源可用窗口；为空表示不裁剪
     * @return 升序候选开始时间
     */
    private static List<LocalDateTime> buildStartTimeRange(
            List<TaskPlan> tasks, LocalDateTime base,
            Map<UUID, List<TimeWindow>> windowsByResource) {
        List<LocalDateTime> fullGrid = new ArrayList<>();
        for (int hour = 0; hour <= HORIZON_HOURS; hour += START_TIME_STEP_HOURS) {
            fullGrid.add(base.plusHours(hour));
        }
        List<LocalDateTime> startTimes;
        if (windowsByResource.isEmpty()) {
            startTimes = new ArrayList<>(fullGrid);
        } else {
            int unionSegments = mergedWindows(windowsByResource).size();
            startTimes = new ArrayList<>();
            for (LocalDateTime candidate : fullGrid) {
                if (fitsAnyMovableTask(candidate, tasks)) {
                    startTimes.add(candidate);
                }
            }
            if (startTimes.isEmpty()) {
                // 没有任何候选能完整容纳任务（例如所有任务时长都超过窗口）时退回完整网格，
                // 避免候选为空导致求解除零；不可行由硬约束给出负分。
                startTimes = new ArrayList<>(fullGrid);
            }
            LOGGER.info("开始时间候选裁剪: {} -> {} 个（按任务时长完整落入窗口，窗口并集 {} 段）",
                    fullGrid.size(), startTimes.size(), unionSegments);
        }
        for (TaskPlan task : tasks) {
            // 已赋值起点必须属于值域：Timefold 初始化校验会拒绝“赋值不在值域”的实体
            // （已用回归用例验证，移除非法冻结起点会让求解直接 FAILED）。因此这里不做过滤，
            // 非法冻结起点的可见性由 placeFrozenTask 日志、metrics 计数与 calendarAvailability
            // 硬约束共同保证，而不是把它从值域里“藏起来”。
            LocalDateTime pinned = task.getPinnedStartTime();
            if (pinned != null && !startTimes.contains(pinned)) {
                startTimes.add(pinned);
            }
            // 预热初始解的时间可能落在 horizon 外；原样加入候选以保持预热保真。
            // 非冻结任务仍可移动，非法预热起点由局部搜索修回窗口；修不掉则硬违约如实可见。
            LocalDateTime warm = task.getStartTime();
            if (warm != null && !startTimes.contains(warm)) {
                startTimes.add(warm);
            }
        }
        startTimes.sort(Comparator.naturalOrder());
        return startTimes;
    }

    /**
     * 判断任务在给定起点下的占用块是否完整落在其全部可用时段内。
     *
     * <p>占用块口径与 {@link #fitsAnyMovableTask} 一致：静态换型 + 加工时长。与换型矩阵的
     * 差异见该方法的说明（矩阵换型可能更长，此处只做候选裁剪的近似）。</p>
     *
     * @param task 计划任务
     * @param start 开始时间
     * @return {@code true} 表示完整落在可用时段内；无窗口要求视为 7×24
     */
    private static boolean allowedAt(TaskPlan task, LocalDateTime start) {
        List<ResourceAvailability> requirements = task.getAvailabilityRequirements();
        if (requirements.isEmpty()) {
            return true;
        }
        LocalDateTime end = start.plusSeconds(
                task.getStaticSetupSeconds() + task.getRunSeconds());
        return requirements.stream().allMatch(requirement -> requirement.allows(start, end));
    }

    /**
     * 判断开始时间是否至少能被一个可移动任务完整占用。
     *
     * <p>占用块按静态换型 + 加工时长估算（与任务初始的 {@code getEndTime()} 口径一致），
     * 并要求完整落在该任务的每个可用时段要求内；任一项要求不满足，该任务就不能使用该开始时间。
     * 只要存在一个可移动任务可用，候选就保留——这样只剔除对全部任务都不可用的整点，
     * 不会误删短任务合法但贴边的候选。</p>
     *
     * @param start 候选开始时间
     * @param tasks 计划任务
     * @return {@code true} 表示至少一个可移动任务可完整占用
     */
    private static boolean fitsAnyMovableTask(LocalDateTime start, List<TaskPlan> tasks) {
        for (TaskPlan task : tasks) {
            if (task.isFrozen() || task.isPinned()) {
                continue;
            }
            List<ResourceAvailability> requirements = task.getAvailabilityRequirements();
            if (requirements.isEmpty()) {
                // 空要求等价 7×24：任何候选都可用，此时不裁剪。
                return true;
            }
            LocalDateTime end = start.plusSeconds(
                    task.getStaticSetupSeconds() + task.getRunSeconds());
            if (requirements.stream().allMatch(requirement -> requirement.allows(start, end))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 合并所有资源的可用窗口为按时间升序、互不重叠的并集。
     *
     * @param windowsByResource 资源可用窗口
     * @return 合并后的窗口
     */
    private static List<TimeWindow> mergedWindows(Map<UUID, List<TimeWindow>> windowsByResource) {
        List<TimeWindow> all = new ArrayList<>();
        for (List<TimeWindow> windows : windowsByResource.values()) {
            all.addAll(windows);
        }
        all.sort(Comparator.comparing(TimeWindow::start));
        List<TimeWindow> merged = new ArrayList<>();
        for (TimeWindow window : all) {
            if (!merged.isEmpty() && !window.start().isAfter(merged.get(merged.size() - 1).end())) {
                TimeWindow last = merged.remove(merged.size() - 1);
                merged.add(new TimeWindow(last.start(),
                        window.end().isAfter(last.end()) ? window.end() : last.end()));
            } else {
                merged.add(window);
            }
        }
        return merged;
    }

    private static TaskAssignment toAssignment(ResourcePlan resource, TaskPlan task, int index) {
        LocalDateTime setupStart = task.getStartTime();
        LocalDateTime setupEnd = task.getProcessingStartTime();
        LocalDateTime end = task.getEndTime();
        return new TaskAssignment(
                null,
                task.getOrderId(),
                task.getOrderNo(),
                task.getOperationIndex(),
                task.getOperationName(),
                task.getOperationTemplateId(),
                resource.getResourceId(),
                resource.getCode(),
                task.getSecondaryResourceIds(),
                toOffset(setupStart),
                toOffset(setupEnd),
                toOffset(setupEnd),
                toOffset(end),
                task.getQty(),
                index,
                task.isFrozen() ? "FROZEN" : "PLANNED",
                task.isFrozen(),
                null,
                task.getLockType(),
                toOffset(task.getLockedFrom()),
                toOffset(task.getLockedTo()),
                task.getLockedResourceIds());
    }

    private static String message(
            HardSoftScore score, SchedulePlan plan, String status, int unassignedTaskCount) {
        StringBuilder message = new StringBuilder("Timefold 求解完成: ").append(score);
        if ("TIMEOUT".equals(status)) {
            message.append("；到达时限仍存在 ").append(unassignedTaskCount)
                    .append(" 个未排位任务，不发可行解、丢弃最优部分解、不落库");
        } else if (unassignedTaskCount > 0) {
            message.append("；存在 ").append(unassignedTaskCount).append(" 个未排位任务");
        }
        List<String> fallbacks = new ArrayList<>();
        if (plan.isCalendarFallback()) {
            fallbacks.add("资源可用时段缺失，按 7×24 计算");
        }
        if (plan.isDependencyFallback()) {
            fallbacks.add("工序依赖缺失，按 operationIndex 串行补 FS/0");
        }
        if (plan.isSetupMatrixFallback()) {
            fallbacks.add("换型矩阵为空，回退静态 setup_seconds");
        }
        if (!fallbacks.isEmpty()) {
            message.append("；降级: ").append(String.join("；", fallbacks));
        }
        int frozenStartOutsideCalendar = frozenStartOutsideCalendarCount(plan.getTasks());
        if (frozenStartOutsideCalendar > 0) {
            message.append("；存在 ").append(frozenStartOutsideCalendar)
                    .append(" 个冻结任务起点落在可用时段外，按固定解保留并判为不可行")
                    .append("（见 metrics.frozenStartOutsideCalendarCount 与 calendarAvailability 归因）");
        }
        return message.toString();
    }

    private static LocalDateTime frozenStartTime(TaskAssignment frozen) {
        if (frozen.setupStartTime() != null) {
            return toLocal(frozen.setupStartTime());
        }
        if (frozen.startTime() != null) {
            return toLocal(frozen.startTime());
        }
        return null;
    }

    private static LocalDateTime toLocal(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }

    private static String taskKey(UUID orderId, int operationIndex) {
        return String.valueOf(orderId) + ':' + operationIndex;
    }
}
