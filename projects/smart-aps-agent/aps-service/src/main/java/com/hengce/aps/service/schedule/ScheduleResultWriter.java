package com.hengce.aps.service.schedule;

import com.hengce.aps.common.exception.BusinessException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.NewTaskRow;
import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.TaskAssignment;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 求解结果落库：事务内清理旧任务、写入 task、回填 ConstraintJustification 的 taskIds。
 *
 * <p>{@code task} 表没有 {@code setup_seconds} 列，换型工时以
 * {@code setup_end_time - setup_start_time} 持久化，且始终满足 C2 口径
 * {@code setupEndTime = startTime}、{@code setupStartTime = startTime - setup_seconds}。</p>
 */
@Service
public class ScheduleResultWriter {

    /**
     * 资源不重叠归因编码。
     */
    static final String CODE_RESOURCE_NO_OVERLAP = "resourceNoOverlap";

    /**
     * 工序依赖归因编码。
     */
    static final String CODE_OPERATION_DEPENDENCY = "operationDependency";

    /**
     * 排产持久层。
     */
    private final ScheduleRepository scheduleRepository;

    /**
     * 构造写入器。
     *
     * @param scheduleRepository 排产持久层
     */
    public ScheduleResultWriter(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    /**
     * 事务内写入本轮求解的任务与归因。
     *
     * @param assembled 装配结果
     * @param solution 求解结果
     * @param solveTaskId 求解任务主键
     * @return 写入统计
     */
    @Transactional(rollbackFor = Exception.class)
    public WriteOutcome persist(ScheduleProblemAssembler.AssembledProblem assembled,
                                ScheduleSolution solution, long solveTaskId) {
        List<TaskAssignment> assignments = solution.assignments().stream()
                .filter(assignment -> assignment.startTime() != null
                        && assignment.endTime() != null
                        && assignment.resourceId() != null)
                .toList();
        for (TaskAssignment assignment : assignments) {
            if (!assignment.endTime().isAfter(assignment.startTime())) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "求解结果存在 endTime 不晚于 startTime 的任务: "
                                + assignment.orderNo() + "/工序" + assignment.operationIndex());
            }
        }

        // 硬要求：完整性校验必须在 deleteTasks 之前（同一事务内）。
        // 否则“先删旧任务、再写 0 行、然后提交”会把已排好的结果静默清空。
        int expectedTaskCount = assembled.expectedTaskCount();
        if (assignments.size() != expectedTaskCount) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "求解结果不完整，拒绝落库（未删除既有任务）：期望 " + expectedTaskCount
                            + " 个任务，实际排位 " + assignments.size() + " 个");
        }

        Set<UUID> writeOrderIds = new HashSet<>(assembled.orderIds());
        List<PersistedAssignment> persisted = new ArrayList<>(assignments.size());
        Map<UUID, List<TaskAssignment>> byResource = new LinkedHashMap<>();
        for (TaskAssignment assignment : assignments) {
            byResource.computeIfAbsent(assignment.resourceId(), key -> new ArrayList<>()).add(assignment);
        }
        for (List<TaskAssignment> resourceAssignments : byResource.values()) {
            resourceAssignments.sort(Comparator
                    .comparing(TaskAssignment::startTime)
                    .thenComparing(assignment -> assignment.orderNo() == null ? "" : assignment.orderNo())
                    .thenComparingInt(TaskAssignment::operationIndex));
            for (int index = 0; index < resourceAssignments.size(); index++) {
                TaskAssignment assignment = resourceAssignments.get(index);
                if (writeOrderIds.contains(assignment.orderId())) {
                    persisted.add(new PersistedAssignment(assignment, index + 1));
                }
            }
        }
        if (persisted.size() != assembled.expectedWriteTaskCount()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "求解结果落库口径不一致，拒绝落库（未删除既有任务）：期望 " + assembled.expectedWriteTaskCount()
                            + " 个任务，可写入 " + persisted.size() + " 个");
        }

        // 只删本次题面内的工序；窗口外已完工的 FROZEN 工序不在题面内，必须保留真实执行记录。
        Map<UUID, Set<Integer>> solveOperations = new LinkedHashMap<>();
        for (SchedulingOrder order : assembled.problem().orders()) {
            if (writeOrderIds.contains(order.orderId())) {
                solveOperations.put(order.orderId(), order.operations().stream()
                        .map(SchedulingOperation::operationIndex)
                        .collect(Collectors.toSet()));
            }
        }
        List<Long> removedTaskIds = scheduleRepository.deleteTasks(
                assembled.tenantId(), assembled.sandboxId(), solveOperations);
        scheduleRepository.deleteJustificationsByTaskIds(removedTaskIds);

        Map<String, Long> taskIdByLabel = new LinkedHashMap<>();
        Map<UUID, List<Long>> taskIdsByResource = new LinkedHashMap<>();
        Map<String, List<Long>> taskIdsByOrder = new LinkedHashMap<>();
        List<Long> insertedTaskIds = new ArrayList<>();
        int written = 0;
        for (PersistedAssignment item : persisted) {
            TaskAssignment assignment = item.assignment();
            OffsetDateTime setupEnd = assignment.startTime();
            OffsetDateTime setupStart = assignment.setupStartTime();
            if (setupStart == null || setupStart.isAfter(setupEnd)) {
                setupStart = setupEnd;
            }
            long taskId = scheduleRepository.insertTask(new NewTaskRow(
                    assembled.tenantId(),
                    assignment.orderId(),
                    assembled.sandboxId(),
                    assignment.operationIndex(),
                    assignment.operationTemplateId(),
                    assignment.resourceId(),
                    assignment.secondaryResourceIds(),
                    assignment.startTime(),
                    assignment.endTime(),
                    setupStart,
                    setupEnd,
                    assignment.qty() == null ? BigDecimal.ONE : assignment.qty(),
                    assignment.status() == null ? "PLANNED" : assignment.status(),
                    item.sequenceInResource(),
                    1,
                    assignment.lockType(),
                    assignment.lockedFrom(),
                    assignment.lockedTo(),
                    assignment.lockedResourceIds()));
            written++;
            insertedTaskIds.add(taskId);
            taskIdByLabel.put(label(assignment.orderNo(), assignment.operationIndex()), taskId);
            taskIdsByResource.computeIfAbsent(assignment.resourceId(), key -> new ArrayList<>()).add(taskId);
            taskIdsByOrder.computeIfAbsent(assignment.orderNo() == null ? "" : assignment.orderNo(),
                    key -> new ArrayList<>()).add(taskId);
        }

        List<ScheduleRepository.OverlapRow> overlaps = scheduleRepository.findResourceOverlaps(
                assembled.tenantId(), assembled.sandboxId(), insertedTaskIds);
        if (!overlaps.isEmpty()) {
            throw new ScheduleIntegrityException(
                    "求解结果落库后整表重叠校验失败（本次写入任务参与的重叠对 " + overlaps.size() + " 对）: "
                            + overlaps.stream()
                            .map(row -> row.firstTaskId() + "/" + row.secondTaskId()
                                    + "@" + row.resourceId() + " [" + row.firstStart() + ","
                                    + row.firstEnd() + ") vs [" + row.secondStart() + ","
                                    + row.secondEnd() + ")")
                            .collect(java.util.stream.Collectors.joining("; ")));
        }

        int justificationCount = writeJustifications(assembled, solution, solveTaskId, persisted,
                taskIdByLabel, taskIdsByResource, taskIdsByOrder);
        return new WriteOutcome(written, justificationCount);
    }

    private int writeJustifications(ScheduleProblemAssembler.AssembledProblem assembled,
                                    ScheduleSolution solution, long solveTaskId,
                                    List<PersistedAssignment> persisted, Map<String, Long> taskIdByLabel,
                                    Map<UUID, List<Long>> taskIdsByResource,
                                    Map<String, List<Long>> taskIdsByOrder) {
        if (!solution.justifications().isEmpty()) {
            List<Long> allTaskIds = taskIdByLabel.values().stream().toList();
            int count = 0;
            for (ConstraintJustification justification : solution.justifications()) {
                List<Long> taskIds = matchTaskIds(justification.description(), taskIdByLabel);
                if (taskIds.isEmpty()) {
                    taskIds = allTaskIds;
                }
                scheduleRepository.insertJustification(assembled.tenantId(), solveTaskId,
                        justification.constraintCode(), justification.type(), taskIds,
                        justification.description(), justification.weight(), justification.deduction());
                count++;
            }
            return count;
        }
        int count = 0;
        Map<UUID, String> resourceCodes = new LinkedHashMap<>();
        for (TaskAssignment assignment : persisted.stream().map(PersistedAssignment::assignment).toList()) {
            resourceCodes.putIfAbsent(assignment.resourceId(),
                    assignment.resourceCode() == null ? assignment.resourceId().toString() : assignment.resourceCode());
        }
        for (Map.Entry<UUID, List<Long>> entry : taskIdsByResource.entrySet()) {
            scheduleRepository.insertJustification(assembled.tenantId(), solveTaskId,
                    CODE_RESOURCE_NO_OVERLAP, "HARD", entry.getValue(),
                    "资源 " + resourceCodes.get(entry.getKey()) + " 上 " + entry.getValue().size()
                            + " 个任务串行占用，无时间重叠", null, BigDecimal.ZERO);
            count++;
        }
        for (Map.Entry<String, List<Long>> entry : taskIdsByOrder.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            scheduleRepository.insertJustification(assembled.tenantId(), solveTaskId,
                    CODE_OPERATION_DEPENDENCY, "HARD", entry.getValue(),
                    "订单 " + entry.getKey() + " 的 " + entry.getValue().size()
                            + " 道工序按前序依赖与 lag 排程", null, BigDecimal.ZERO);
            count++;
        }
        return count;
    }

    private static List<Long> matchTaskIds(String description, Map<String, Long> taskIdByLabel) {
        if (description == null) {
            return List.of();
        }
        List<Long> matched = new ArrayList<>();
        for (Map.Entry<String, Long> entry : taskIdByLabel.entrySet()) {
            if (description.contains(entry.getKey())) {
                matched.add(entry.getValue());
            }
        }
        return matched;
    }

    private static String label(String orderNo, int operationIndex) {
        return (orderNo == null ? "" : orderNo) + "/工序" + operationIndex;
    }

    /**
     * 已写入任务并分配资源内顺序的分配。
     *
     * @param assignment 求解分配
     * @param sequenceInResource 资源内顺序，1 起始
     */
    private record PersistedAssignment(TaskAssignment assignment, int sequenceInResource) {
    }

    /**
     * 落库统计。
     *
     * @param taskCount 写入任务数
     * @param justificationCount 写入归因数
     */
    public record WriteOutcome(int taskCount, int justificationCount) {
    }
}
