package com.hengce.aps.service.schedule.infeasibility;

import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository.JustificationRow;
import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.duration.OperationRunDuration;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReasonCode;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 确定性证据采集器（B1 归因内核）。
 *
 * <p>证据只来自 {@link ScheduleProblem} 与既有 {@code constraint_justification} 行：</p>
 * <ol>
 *   <li>按题面重放谓词：在 {@code [base, base+168h]} 的小时网格上，逐工序复算资源窗口
 *       是否容纳整块占用（时长口径与 {@code TimefoldModelBuilder} 一致）；</li>
 *   <li>冻结/锁定任务被视为不可移动，复算其固定时段是否落在资源可用窗口内；</li>
 *   <li>冻结任务之间按资源占用切片做重叠配对（主资源 {@code [setupStart, end)}，
 *       辅助资源 {@code [start, end)}）；</li>
 *   <li>按主资源聚合需求工时与可用工时，得到缺口；</li>
 *   <li>既有归因行按 {@code constraintId} 聚合为违反规则。</li>
 * </ol>
 *
 * <p><b>精度损失</b>：Timefold 2.8 社区版没有 enterprise 的 {@code ConstraintMatchTotal}，
 * 因此得不到“按约束的硬分分解”；本采集器只把能够从题面结构必然推出的冲突作为证明，
 * 其余情况一律停在 {@code PARTIAL_EVIDENCE}。</p>
 */
public final class EvidenceCollector {

    /**
     * 求解视野（小时），与 {@code TimefoldModelBuilder.HORIZON_HOURS} 一致。
     */
    static final int HORIZON_HOURS = 168;

    /**
     * 每类证据上限（C51）。
     */
    static final int MAX_ITEMS = 8;

    /**
     * 开始时间网格步长（小时），与 {@code TimefoldModelBuilder} 一致。
     */
    private static final int START_STEP_HOURS = 1;

    /**
     * 每小时秒数。
     */
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    /**
     * 资源锁类型常量。
     */
    private static final String LOCK_RESOURCE = "RESOURCE";

    /**
     * 硬约束评分影响常量。
     */
    private static final String IMPACT_HARD = "HARD";

    /**
     * C51 原因码优先级（数值越大越优先展示）。
     */
    private static final int PRIORITY_NO_ROUTING = 1;

    /**
     * C51 原因码优先级。
     */
    private static final int PRIORITY_NO_RESOURCE = 2;

    /**
     * C51 原因码优先级。
     */
    private static final int PRIORITY_OTHER = 3;

    /**
     * C51 原因码优先级。
     */
    private static final int PRIORITY_HORIZON = 4;

    /**
     * C51 原因码优先级。
     */
    private static final int PRIORITY_WINDOW = 5;

    /**
     * C51 原因码优先级。
     */
    private static final int PRIORITY_FROZEN = 6;

    private EvidenceCollector() {
    }

    /**
     * 采集证据。
     *
     * @param problem 题面（必须已带 {@code baseTime}）
     * @param justifications 既有归因行
     * @param calendarFallback 本次求解是否发生日历回退
     * @return 证据集合
     */
    public static EvidenceBundle collect(ScheduleProblem problem,
                                         List<JustificationRow> justifications,
                                         boolean calendarFallback) {
        Context context = new Context(problem, justifications, calendarFallback);
        context.analyze();
        return context.toBundle();
    }

    /**
     * 一次采集的可变上下文；把状态收敛在实例字段，避免长参数列表。
     */
    private static final class Context {

        private final ScheduleProblem problem;

        private final List<JustificationRow> justifications;

        private final boolean calendarFallback;

        private final Map<UUID, SchedulingResource> resourceById = new LinkedHashMap<>();

        private final Map<String, TaskAssignment> existingByKey = new HashMap<>();

        private final Map<String, InfeasibilityReport.BlockingTask> blockingByKey = new LinkedHashMap<>();

        private final List<InfeasibilityReport.ConflictPair> conflicts = new ArrayList<>();

        private final List<InfeasibilityReport.ResourceWindow> windows = new ArrayList<>();

        private final List<InfeasibilityReport.ViolatedRule> ruleAggregates = new ArrayList<>();

        private final List<String> assumptions = new ArrayList<>();

        private final Set<String> proofNotes = new LinkedHashSet<>();

        private final LocalDateTime base;

        private final LocalDateTime horizonEnd;

        private boolean proven;

        private Context(ScheduleProblem problem, List<JustificationRow> justifications, boolean calendarFallback) {
            this.problem = Objects.requireNonNull(problem, "problem 不能为空");
            this.justifications = List.copyOf(justifications == null ? List.of() : justifications);
            this.calendarFallback = calendarFallback;
            this.base = problem.baseTime() == null
                    ? LocalDateTime.now(ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0)
                    : problem.baseTime();
            this.horizonEnd = base.plusHours(HORIZON_HOURS);
            for (SchedulingResource resource : problem.resources()) {
                resourceById.put(resource.resourceId(), resource);
            }
            for (TaskAssignment assignment : problem.frozenAssignments()) {
                existingByKey.put(taskKey(assignment.orderId(), assignment.operationIndex()), assignment);
            }
        }

        private void analyze() {
            assumptions.add("题面基准时间 base=" + base + "（UTC），视野 " + HORIZON_HOURS + "h");
            assumptions.add("证据基于本次归因拿到的题面快照（终态事件引用或 solve_task.config 重放）");
            assumptions.add("冻结/锁定任务视为不可移动；浮动资源任务不计入单资源缺口");
            assumptions.add("未考虑物料齐套、人员技能等 ScheduleProblem 之外的事实");
            assumptions.add("证据只来自 ScheduleProblem 与既有 constraint_justification，未调用 LLM");
            if (calendarFallback) {
                assumptions.add("本次求解发生日历回退（资源可用时段缺失按 7×24），窗口证据可能偏宽");
            }
            analyzeOrders();
            analyzeFrozenCalendar();
            analyzeFrozenConflicts();
            analyzeResourceGaps();
            addViolatedRules();
        }

        private void analyzeOrders() {
            for (SchedulingOrder order : problem.orders()) {
                if (order.operations().isEmpty()) {
                    addBlocking(blockingKey(order.orderNo(), null),
                            new InfeasibilityReport.BlockingTask(existingTaskId(order, null),
                                    order.orderNo(), null, null, InfeasibilityReasonCode.NO_ROUTING,
                                    "订单 " + order.orderNo() + " 没有生效工艺路线工序"),
                            false, null);
                    continue;
                }
                for (SchedulingOperation operation : order.operations()) {
                    analyzeOperation(order, operation);
                }
            }
        }

        private void analyzeOperation(SchedulingOrder order, SchedulingOperation operation) {
            TaskAssignment existing = existingByKey.get(
                    taskKey(order.orderId(), operation.operationIndex()));
            SchedulingResource primary = nominalResource(operation, existing);
            if (primary == null) {
                addBlocking(operationKey(order, operation),
                        blocking(order, operation, existing, InfeasibilityReasonCode.NO_RESOURCE,
                                "工序 " + operationLabel(operation) + " 找不到可用主资源"),
                        false, null);
                return;
            }
            if (!secondaryResourcesPresent(operation)) {
                addBlocking(operationKey(order, operation),
                        blocking(order, operation, existing, InfeasibilityReasonCode.NO_RESOURCE,
                                "工序 " + operationLabel(operation) + " 的辅助资源不在题面资源快照中"),
                        false, null);
                return;
            }
            long durationSeconds;
            long runSeconds;
            try {
                runSeconds = OperationRunDuration.seconds(primary, operation, order.qty());
                durationSeconds = ceilingSeconds(operation.setupSeconds()) + runSeconds;
            } catch (RuntimeException exception) {
                addBlocking(operationKey(order, operation),
                        blocking(order, operation, existing, InfeasibilityReasonCode.OTHER,
                                "工序 " + operationLabel(operation) + " 时长不可计算: " + exception.getMessage()),
                        false, null);
                return;
            }
            LocalDateTime earliest = order.notBefore() == null ? base
                    : order.notBefore().isAfter(base) ? order.notBefore() : base;
            if (earliest.isAfter(horizonEnd)) {
                markUnplaceable(order, operation, existing, InfeasibilityReasonCode.HORIZON_EXCEEDED,
                        "订单 " + order.orderNo() + " 的 notBefore=" + earliest
                                + " 晚于求解视野末 " + horizonEnd);
                return;
            }
            if (!hasFeasibleStart(operation, primary, durationSeconds, earliest)) {
                markUnplaceable(order, operation, existing, InfeasibilityReasonCode.RESOURCE_WINDOW_SHORT,
                        "工序 " + operationLabel(operation) + " 在 " + base + " ~ " + horizonEnd
                                + " 内不存在满足资源 " + primary.code() + " 可用窗口的整块开始时间（时长 "
                                + formatHours(durationSeconds) + "h）");
            }
        }

        private void markUnplaceable(SchedulingOrder order, SchedulingOperation operation,
                                     TaskAssignment existing, InfeasibilityReasonCode reasonCode,
                                     String reason) {
            addBlocking(operationKey(order, operation),
                    blocking(order, operation, existing, reasonCode, reason), true,
                    reason + "；runSeconds 与 TaskPlan 口径一致（OperationRunDuration）");
        }

        private boolean hasFeasibleStart(SchedulingOperation operation, SchedulingResource primary,
                                         long durationSeconds, LocalDateTime earliest) {
            for (int hour = 0; hour <= HORIZON_HOURS; hour += START_STEP_HOURS) {
                LocalDateTime start = base.plusHours(hour);
                if (start.isBefore(earliest)) {
                    continue;
                }
                if (allowsAll(operation, primary, start, start.plusSeconds(durationSeconds))) {
                    return true;
                }
            }
            return false;
        }

        private boolean allowsAll(SchedulingOperation operation, SchedulingResource primary,
                                  LocalDateTime start, LocalDateTime end) {
            if (!allows(primary, start, end)) {
                return false;
            }
            for (UUID secondaryId : operation.secondaryResourceIds()) {
                SchedulingResource secondary = resourceById.get(secondaryId);
                if (secondary == null || !allows(secondary, start, end)) {
                    return false;
                }
            }
            return true;
        }

        private boolean allows(SchedulingResource resource, LocalDateTime start, LocalDateTime end) {
            if (resource.availabilityWindows().isEmpty()) {
                return true;
            }
            for (AvailabilityWindow window : resource.availabilityWindows()) {
                LocalDateTime windowStart = toLocal(window.start());
                LocalDateTime windowEnd = toLocal(window.end());
                if (!start.isBefore(windowStart) && !end.isAfter(windowEnd)) {
                    return true;
                }
            }
            return false;
        }

        private void analyzeFrozenCalendar() {
            for (TaskAssignment assignment : problem.frozenAssignments()) {
                if (!assignment.frozen() || assignment.startTime() == null
                        || assignment.resourceId() == null || assignment.taskId() == null) {
                    continue;
                }
                SchedulingResource primary = resourceById.get(assignment.resourceId());
                if (primary == null) {
                    continue;
                }
                LocalDateTime start = toLocal(assignment.setupStartTime() != null
                        ? assignment.setupStartTime() : assignment.startTime());
                LocalDateTime end = toLocal(assignment.endTime());
                if (!allowsWithSecondaries(assignment, primary, start, end)) {
                    String note = "冻结任务 " + assignment.orderNo() + "#" + assignment.operationIndex()
                            + "（taskId=" + assignment.taskId() + "）被钉在 " + start + " ~ " + end
                            + "，但与资源 " + primary.code() + " 的可用窗口冲突";
                    addBlocking(blockingKey(assignment.orderNo(), assignment.operationIndex()),
                            new InfeasibilityReport.BlockingTask(assignment.taskId(), assignment.orderNo(),
                                    assignment.operationName(), assignment.operationIndex(),
                                    InfeasibilityReasonCode.FROZEN_CONFLICT, note),
                            true, note);
                }
            }
        }

        private boolean allowsWithSecondaries(TaskAssignment assignment, SchedulingResource primary,
                                              LocalDateTime start, LocalDateTime end) {
            if (!allows(primary, start, end)) {
                return false;
            }
            for (UUID secondaryId : assignment.secondaryResourceIds()) {
                SchedulingResource secondary = resourceById.get(secondaryId);
                if (secondary == null || !allows(secondary, start, end)) {
                    return false;
                }
            }
            return true;
        }

        private void analyzeFrozenConflicts() {
            List<FrozenSlot> slots = new ArrayList<>();
            for (TaskAssignment assignment : problem.frozenAssignments()) {
                if (!assignment.frozen() || assignment.taskId() == null || assignment.startTime() == null) {
                    continue;
                }
                addSlots(slots, assignment);
            }
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < slots.size(); i++) {
                for (int j = i + 1; j < slots.size(); j++) {
                    FrozenSlot first = slots.get(i);
                    FrozenSlot second = slots.get(j);
                    addConflict(seen, first, second);
                }
            }
        }

        private void addSlots(List<FrozenSlot> slots, TaskAssignment assignment) {
            LocalDateTime primaryStart = toLocal(assignment.setupStartTime() != null
                    ? assignment.setupStartTime() : assignment.startTime());
            LocalDateTime end = toLocal(assignment.endTime());
            if (assignment.resourceId() != null) {
                slots.add(new FrozenSlot(assignment, assignment.resourceId(), primaryStart, end));
            }
            LocalDateTime processStart = toLocal(assignment.startTime());
            for (UUID secondaryId : assignment.secondaryResourceIds()) {
                slots.add(new FrozenSlot(assignment, secondaryId, processStart, end));
            }
        }

        private void addConflict(Set<String> seen, FrozenSlot first, FrozenSlot second) {
            if (!first.resourceId().equals(second.resourceId())
                    || first.taskId() == second.taskId()) {
                return;
            }
            LocalDateTime overlapStart = first.start().isAfter(second.start())
                    ? first.start() : second.start();
            LocalDateTime overlapEnd = first.end().isBefore(second.end()) ? first.end() : second.end();
            if (!overlapEnd.isAfter(overlapStart)) {
                return;
            }
            long taskA = Math.min(first.taskId(), second.taskId());
            long taskB = Math.max(first.taskId(), second.taskId());
            String key = taskA + ":" + taskB + ":" + first.resourceId() + ":" + overlapStart;
            if (!seen.add(key)) {
                return;
            }
            conflicts.add(new InfeasibilityReport.ConflictPair(taskA, taskB, first.resourceId(),
                    toOffset(overlapStart), toOffset(overlapEnd)));
            String note = "冻结任务 " + taskA + " 与 " + taskB + " 在资源 "
                    + first.resourceId() + " 上重叠 " + overlapStart + " ~ " + overlapEnd
                    + "；两者均不可移动 → 硬约束必违约";
            addBlocking(blockingKey(first.orderNo(), first.operationIndex()),
                    frozenBlocking(first.taskId(), first.orderNo(), first.operationName(),
                            first.operationIndex(), note), true, note);
            addBlocking(blockingKey(second.orderNo(), second.operationIndex()),
                    frozenBlocking(second.taskId(), second.orderNo(), second.operationName(),
                            second.operationIndex(), note), true, note);
        }

        private void analyzeResourceGaps() {
            Map<UUID, Long> requiredAll = new HashMap<>();
            Map<UUID, Long> requiredNonFrozen = new HashMap<>();
            for (SchedulingOrder order : problem.orders()) {
                for (SchedulingOperation operation : order.operations()) {
                    accumulateRequirement(requiredAll, requiredNonFrozen, order, operation);
                }
            }
            for (Map.Entry<UUID, Long> entry : requiredAll.entrySet()) {
                SchedulingResource resource = resourceById.get(entry.getKey());
                if (resource == null) {
                    continue;
                }
                long availableSeconds = availableSeconds(resource);
                long requiredSeconds = entry.getValue();
                if (requiredSeconds <= availableSeconds) {
                    continue;
                }
                long nonFrozenSeconds = requiredNonFrozen.getOrDefault(entry.getKey(), 0L);
                windows.add(new InfeasibilityReport.ResourceWindow(resource.resourceId(),
                        toOffset(windowStart(resource)), toOffset(windowEnd(resource)),
                        hours(requiredSeconds), hours(requiredSeconds - availableSeconds)));
                if (nonFrozenSeconds > availableSeconds) {
                    proven = true;
                    proofNotes.add("资源 " + resource.code() + " 视野内可用 " + hours(availableSeconds).toPlainString()
                            + "h，但未冻结的固定主资源任务已需 " + hours(nonFrozenSeconds).toPlainString()
                            + "h（run-only 下界）→ 产能必然不足");
                }
            }
        }

        private void accumulateRequirement(Map<UUID, Long> requiredAll,
                                           Map<UUID, Long> requiredNonFrozen,
                                           SchedulingOrder order, SchedulingOperation operation) {
            if (operation.primaryResourceId() == null) {
                return;
            }
            SchedulingResource resource = resourceById.get(operation.primaryResourceId());
            if (resource == null) {
                return;
            }
            long runSeconds;
            try {
                runSeconds = OperationRunDuration.seconds(resource, operation, order.qty());
            } catch (RuntimeException exception) {
                return;
            }
            requiredAll.merge(resource.resourceId(), runSeconds, Long::sum);
            TaskAssignment existing = existingByKey.get(
                    taskKey(order.orderId(), operation.operationIndex()));
            if (existing == null || !existing.frozen()) {
                requiredNonFrozen.merge(resource.resourceId(), runSeconds, Long::sum);
            }
        }

        private long availableSeconds(SchedulingResource resource) {
            if (resource.availabilityWindows().isEmpty()) {
                return (long) HORIZON_HOURS * 3600L;
            }
            long total = 0L;
            long horizonStart = base.toEpochSecond(ZoneOffset.UTC);
            long horizonEndEpoch = horizonEnd.toEpochSecond(ZoneOffset.UTC);
            for (AvailabilityWindow window : resource.availabilityWindows()) {
                long from = Math.max(window.start().toEpochSecond(), horizonStart);
                long to = Math.min(window.end().toEpochSecond(), horizonEndEpoch);
                if (to > from) {
                    total += to - from;
                }
            }
            return total;
        }

        private LocalDateTime windowStart(SchedulingResource resource) {
            if (resource.availabilityWindows().isEmpty()) {
                return base;
            }
            LocalDateTime first = toLocal(resource.availabilityWindows().get(0).start());
            return first.isAfter(base) ? first : base;
        }

        private LocalDateTime windowEnd(SchedulingResource resource) {
            if (resource.availabilityWindows().isEmpty()) {
                return horizonEnd;
            }
            List<AvailabilityWindow> source = resource.availabilityWindows();
            LocalDateTime last = toLocal(source.get(source.size() - 1).end());
            return last.isBefore(horizonEnd) ? last : horizonEnd;
        }

        private void addViolatedRules() {
            Map<String, RuleAggregate> aggregates = new LinkedHashMap<>();
            for (JustificationRow row : justifications) {
                if (row.constraintId() == null || row.constraintId().isBlank()) {
                    continue;
                }
                RuleAggregate aggregate = aggregates.computeIfAbsent(row.constraintId(),
                        key -> new RuleAggregate(key, row.constraintName()));
                aggregate.count++;
                if (!IMPACT_HARD.equals(row.scoreImpact()) && row.deduction() != null) {
                    aggregate.deduction = aggregate.deduction.add(row.deduction());
                }
            }
            for (RuleAggregate aggregate : aggregates.values()) {
                ruleAggregates.add(new InfeasibilityReport.ViolatedRule(aggregate.code,
                        aggregate.name == null || aggregate.name.isBlank() ? aggregate.code : aggregate.name,
                        aggregate.deduction, aggregate.count));
            }
        }

        private void addBlocking(String key, InfeasibilityReport.BlockingTask task,
                                 boolean proof, String proofNote) {
            InfeasibilityReport.BlockingTask previous = blockingByKey.get(key);
            if (previous == null || priority(task.reasonCode()) > priority(previous.reasonCode())) {
                blockingByKey.put(key, task);
            }
            if (proof) {
                proven = true;
                if (proofNote != null && !proofNote.isBlank()) {
                    proofNotes.add(proofNote);
                }
            }
        }

        private SchedulingResource nominalResource(SchedulingOperation operation, TaskAssignment existing) {
            if (operation.primaryResourceId() != null) {
                return resourceById.get(operation.primaryResourceId());
            }
            if (existing != null && LOCK_RESOURCE.equals(existing.lockType())
                    && !existing.lockedResourceIds().isEmpty()) {
                return existing.lockedResourceIds().stream()
                        .map(resourceById::get)
                        .filter(Objects::nonNull)
                        .min(Comparator.comparing(SchedulingResource::code))
                        .orElse(null);
            }
            return resourceById.values().stream()
                    .min(Comparator.comparing(SchedulingResource::code))
                    .orElse(null);
        }

        private boolean secondaryResourcesPresent(SchedulingOperation operation) {
            for (UUID secondaryId : operation.secondaryResourceIds()) {
                if (!resourceById.containsKey(secondaryId)) {
                    return false;
                }
            }
            return true;
        }

        private InfeasibilityReport.BlockingTask blocking(SchedulingOrder order, SchedulingOperation operation,
                                                          TaskAssignment existing,
                                                          InfeasibilityReasonCode reasonCode, String reason) {
            return new InfeasibilityReport.BlockingTask(existing == null ? null : existing.taskId(),
                    order.orderNo(), operation.operationName(), operation.operationIndex(), reasonCode, reason);
        }

        private InfeasibilityReport.BlockingTask frozenBlocking(long taskId, String orderNo, String operationName,
                                                                int operationIndex, String reason) {
            return new InfeasibilityReport.BlockingTask(taskId, orderNo, operationName, operationIndex,
                    InfeasibilityReasonCode.FROZEN_CONFLICT, reason);
        }

        private Long existingTaskId(SchedulingOrder order, SchedulingOperation operation) {
            if (operation == null) {
                return existingByKey.values().stream()
                        .filter(assignment -> Objects.equals(assignment.orderId(), order.orderId()))
                        .map(TaskAssignment::taskId)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
            }
            TaskAssignment existing = existingByKey.get(taskKey(order.orderId(), operation.operationIndex()));
            return existing == null ? null : existing.taskId();
        }

        private EvidenceBundle toBundle() {
            ruleAggregates.sort(Comparator
                    .comparing(InfeasibilityReport.ViolatedRule::deduction).reversed()
                    .thenComparing(InfeasibilityReport.ViolatedRule::ruleCode));
            List<InfeasibilityReport.BlockingTask> blocking = new ArrayList<>(blockingByKey.values());
            blocking.sort(Comparator
                    .comparing(InfeasibilityReport.BlockingTask::orderNo)
                    .thenComparing(task -> task.operationIndex() == null ? Integer.MIN_VALUE : task.operationIndex()));
            List<InfeasibilityReport.ConflictPair> conflictList = new ArrayList<>(conflicts);
            conflictList.sort(Comparator.comparingLong(InfeasibilityReport.ConflictPair::taskA)
                    .thenComparingLong(InfeasibilityReport.ConflictPair::taskB)
                    .thenComparing(pair -> pair.resourceId().toString()));
            List<InfeasibilityReport.ResourceWindow> windowList = new ArrayList<>(windows);
            windowList.sort(Comparator.comparing(window -> window.resourceId().toString()));
            boolean truncated = blocking.size() > MAX_ITEMS || conflictList.size() > MAX_ITEMS
                    || windowList.size() > MAX_ITEMS || ruleAggregates.size() > MAX_ITEMS;
            return new EvidenceBundle(limit(blocking), limit(conflictList), limit(windowList),
                    limit(ruleAggregates), assumptions, proven, new ArrayList<>(proofNotes), truncated);
        }

        private static <T> List<T> limit(List<T> items) {
            return items.size() <= MAX_ITEMS ? List.copyOf(items) : List.copyOf(items.subList(0, MAX_ITEMS));
        }

        private static int priority(InfeasibilityReasonCode reasonCode) {
            return switch (reasonCode) {
                case NO_ROUTING -> PRIORITY_NO_ROUTING;
                case NO_RESOURCE -> PRIORITY_NO_RESOURCE;
                case HORIZON_EXCEEDED -> PRIORITY_HORIZON;
                case RESOURCE_WINDOW_SHORT -> PRIORITY_WINDOW;
                case FROZEN_CONFLICT -> PRIORITY_FROZEN;
                default -> PRIORITY_OTHER;
            };
        }

        private static long ceilingSeconds(BigDecimal value) {
            if (value == null) {
                return 0L;
            }
            return value.setScale(0, RoundingMode.CEILING).longValueExact();
        }

        private static BigDecimal hours(long seconds) {
            return BigDecimal.valueOf(seconds).divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP);
        }

        private static String formatHours(long seconds) {
            return hours(seconds).stripTrailingZeros().toPlainString();
        }

        private static String operationLabel(SchedulingOperation operation) {
            return operation.operationName() == null || operation.operationName().isBlank()
                    ? operation.operationCode() : operation.operationName();
        }

        private static String blockingKey(String orderNo, Integer operationIndex) {
            return orderNo + "#" + (operationIndex == null ? "-1" : operationIndex);
        }

        private static String operationKey(SchedulingOrder order, SchedulingOperation operation) {
            return blockingKey(order.orderNo(), operation.operationIndex());
        }

        private static String taskKey(UUID orderId, int operationIndex) {
            return orderId + "#" + operationIndex;
        }

        private static LocalDateTime toLocal(OffsetDateTime time) {
            return time == null ? null : time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }

        private static OffsetDateTime toOffset(LocalDateTime time) {
            return time == null ? null : time.atOffset(ZoneOffset.UTC);
        }

        /**
         * 资源占用切片。
         */
        private record FrozenSlot(TaskAssignment assignment, UUID resourceId,
                                  LocalDateTime start, LocalDateTime end) {

            private long taskId() {
                return assignment.taskId();
            }

            private String orderNo() {
                return assignment.orderNo();
            }

            private String operationName() {
                return assignment.operationName();
            }

            private int operationIndex() {
                return assignment.operationIndex();
            }
        }

        /**
         * 规则聚合累加器。
         */
        private static final class RuleAggregate {

            private final String code;

            private final String name;

            private BigDecimal deduction = BigDecimal.ZERO;

            private int count;

            private RuleAggregate(String code, String name) {
                this.code = code;
                this.name = name;
            }
        }
    }
}
