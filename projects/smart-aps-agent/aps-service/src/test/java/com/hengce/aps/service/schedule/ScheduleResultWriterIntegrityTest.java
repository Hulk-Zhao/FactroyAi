package com.hengce.aps.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.common.exception.BusinessException;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.NewTaskRow;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.OverlapRow;
import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.TaskAssignment;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 落库完整性顺序验证：非法/不完整的求解结果必须在 {@code deleteTasks} 之前被拒绝，
 * 既有 task 行必须保持原样（防止“先删后写 0 行”静默清空数据）。
 */
class ScheduleResultWriterIntegrityTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    private static final UUID SANDBOX_ID = UUID.randomUUID();

    private static final UUID ORDER_ID = UUID.randomUUID();

    private static final UUID RESOURCE_ID = UUID.randomUUID();

    private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-11-04T08:00:00+08:00");

    @Test
    void emptyAssignmentsAreRejectedAndExistingRowsSurvive() {
        FakeScheduleRepository repository = new FakeScheduleRepository(4);
        ScheduleResultWriter writer = new ScheduleResultWriter(repository);

        BusinessException error = assertThrows(BusinessException.class,
                () -> writer.persist(assembled(3), solution(List.of()), 7L));

        assertTrue(error.getMessage().contains("求解结果不完整"), error.getMessage());
        assertFalse(repository.deleteCalled, "deleteTasks 不得在完整性校验之前被调用");
        assertEquals(4, repository.existingTaskRowCount, "既有 task 行必须原样保留");
        assertEquals(0, repository.insertedRows.size(), "不得写入任何新行");
    }

    @Test
    void partialAssignmentsAreRejectedAndExistingRowsSurvive() {
        FakeScheduleRepository repository = new FakeScheduleRepository(2);
        ScheduleResultWriter writer = new ScheduleResultWriter(repository);

        BusinessException error = assertThrows(BusinessException.class,
                () -> writer.persist(assembled(3), solution(assignments(1)), 7L));

        assertTrue(error.getMessage().contains("期望 3 个任务，实际排位 1 个"), error.getMessage());
        assertFalse(repository.deleteCalled);
        assertEquals(2, repository.existingTaskRowCount);
        assertEquals(0, repository.insertedRows.size());
    }

    @Test
    void overlapGuardFailsAsIntegrityErrorNotEngineError() {
        OverlapRow overlap = new OverlapRow(1L, 2L, RESOURCE_ID,
                BASE, BASE.plusHours(1), BASE.plusMinutes(30), BASE.plusMinutes(90));
        FakeScheduleRepository repository = new FakeScheduleRepository(1, List.of(overlap));
        ScheduleResultWriter writer = new ScheduleResultWriter(repository);

        ScheduleIntegrityException error = assertThrows(ScheduleIntegrityException.class,
                () -> writer.persist(assembled(1), solution(assignments(1)), 7L),
                "整表重叠是数据完整性守卫，不得抛成引擎异常类型");

        assertTrue(error.getMessage().contains("整表重叠校验失败"), error.getMessage());
        assertTrue(error.getMessage().contains("1") && error.getMessage().contains(RESOURCE_ID.toString()),
                "message 必须保留重叠对明细: " + error.getMessage());
        assertEquals(0, repository.justificationInsertCount, "守卫失败后不得再写归因行");
    }

    @Test
    void completeAssignmentsArePersisted() {
        FakeScheduleRepository repository = new FakeScheduleRepository(2);
        ScheduleResultWriter writer = new ScheduleResultWriter(repository);

        ScheduleResultWriter.WriteOutcome outcome = writer.persist(
                assembled(3), solution(assignments(3)), 7L);

        assertEquals(3, outcome.taskCount());
        assertTrue(repository.deleteCalled);
        assertEquals(3, repository.insertedRows.size());
        assertEquals(1, repository.lastDeletedOperations.size());
    }

    private static ScheduleProblemAssembler.AssembledProblem assembled(int operationCount) {
        List<SchedulingOperation> operations = new ArrayList<>();
        for (int index = 1; index <= operationCount; index++) {
            operations.add(new SchedulingOperation(index, "OP-" + index, "工序" + index,
                    null, BigDecimal.TEN, BigDecimal.ZERO, null, null, List.of(), false));
        }
        SchedulingOrder order = new SchedulingOrder(ORDER_ID, "SO-1", "M-1", BigDecimal.TEN,
                null, "NORMAL", "UNFROZEN", null, operations);
        ScheduleProblem problem = new ScheduleProblem(TENANT_ID.toString(), SANDBOX_ID, 7L, null,
                "FORWARD", List.of(order), List.of(), List.of(), List.of(), List.of());
        return new ScheduleProblemAssembler.AssembledProblem(TENANT_ID, SANDBOX_ID, problem,
                List.of(ORDER_ID));
    }

    private static List<TaskAssignment> assignments(int count) {
        List<TaskAssignment> assignments = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            OffsetDateTime start = BASE.plusHours(index);
            assignments.add(new TaskAssignment(null, ORDER_ID, "SO-1", index, null,
                    UUID.randomUUID(), RESOURCE_ID, "R-1", List.of(), start, start,
                    start, start.plusMinutes(30), BigDecimal.TEN, null, "PLANNED", false, index));
        }
        return assignments;
    }

    private static ScheduleSolution solution(List<TaskAssignment> assignments) {
        return new ScheduleSolution("SOLVED",
                new ScoreFact(BigDecimal.ZERO, BigDecimal.ZERO, "0hard/0soft"),
                assignments,
                List.<ConstraintJustification>of(),
                new SolveMetrics(0, "TIME_LIMIT", List.of(), assignments.size(), Map.of()),
                "ok");
    }

    /**
     * 内存版持久层：记录删除/插入动作，用 {@code existingTaskRowCount} 模拟库内既有行。
     */
    private static final class FakeScheduleRepository extends ScheduleRepository {

        private int existingTaskRowCount;

        private boolean deleteCalled;

        private Map<UUID, Set<Integer>> lastDeletedOperations = Map.of();

        private final List<NewTaskRow> insertedRows = new ArrayList<>();

        private final List<OverlapRow> overlapRows;

        private int justificationInsertCount;

        private long nextId;

        private FakeScheduleRepository(int existingTaskRowCount) {
            this(existingTaskRowCount, List.of());
        }

        private FakeScheduleRepository(int existingTaskRowCount, List<OverlapRow> overlapRows) {
            super(null);
            this.existingTaskRowCount = existingTaskRowCount;
            this.overlapRows = overlapRows;
        }

        @Override
        public List<Long> deleteTasks(UUID tenantId, UUID sandboxId,
                                      Map<UUID, Set<Integer>> operationIndicesByOrder) {
            deleteCalled = true;
            lastDeletedOperations = operationIndicesByOrder;
            existingTaskRowCount = 0;
            return List.of();
        }

        @Override
        public void deleteJustificationsByTaskIds(List<Long> taskIds) {
            // 无既有归因行
        }

        @Override
        public long insertTask(NewTaskRow row) {
            insertedRows.add(row);
            return ++nextId;
        }

        @Override
        public List<OverlapRow> findResourceOverlaps(UUID tenantId, UUID sandboxId, List<Long> taskIds) {
            return overlapRows;
        }

        @Override
        public void insertJustification(UUID tenantId, long solveTaskId, String constraintCode,
                                        String constraintType, List<Long> taskIds, String description,
                                        Integer weight, BigDecimal deduction) {
            justificationInsertCount++;
        }
    }
}
