package com.hengce.aps.service.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogRepository;
import com.hengce.aps.infrastructure.persistence.mapper.calendar.CalendarMapper;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.TaskAdjustmentRepository;
import com.hengce.aps.infrastructure.persistence.schedule.TaskAdjustmentRepository.AdjustTaskRow;
import com.hengce.aps.infrastructure.persistence.schedule.TaskAdjustmentRepository.AdjustmentRow;
import com.hengce.aps.infrastructure.persistence.schedule.TaskAdjustmentRepository.NewSplitTask;
import com.hengce.aps.infrastructure.persistence.schedule.TaskAdjustmentRepository.ResourceRef;
import com.hengce.aps.service.schedule.TaskAdjustService.TaskAdjustCommand;
import com.hengce.aps.service.schedule.TaskAdjustService.TaskAdjustResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * C36 SPLIT/MERGE 单元测试：数量守恒、时间连续、dryRun 不落库、MERGE 合法性、撤销恢复、40903。
 */
@ExtendWith(MockitoExtension.class)
class TaskAdjustSplitMergeTest {

    private static final UUID TENANT = TenantContext.DEFAULT_TENANT_ID;

    private static final UUID SANDBOX = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID ORDER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID OPERATION_TEMPLATE =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID R1 = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final UUID R2 = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 4, 8, 0, 0, 0, ZoneOffset.UTC);

    private static final String KEY = "adjust-key-0001";

    @Mock
    private TaskAdjustmentRepository adjustmentRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private CalendarMapper calendarMapper;

    @Mock
    private ScheduleProblemAssembler assembler;

    @Mock
    private AuditLogRepository auditLogRepository;

    private TaskAdjustService service;

    @BeforeEach
    void setUp() {
        service = new TaskAdjustService(adjustmentRepository, scheduleRepository, calendarMapper,
                assembler, auditLogRepository);
        TenantContext.setTenantId(TENANT);
    }

    @Test
    @DisplayName("SPLIT 两段：源任务保留第一段，新建 1 个子任务，时间按数量比例连续切分")
    void splitTwoSegmentsKeepsFirstSegmentAndCreatesOneTask() {
        AdjustTaskRow row = task(10L, R1, 5, "PLANNED", new BigDecimal("10"), T0, T0.plusHours(2));
        when(adjustmentRepository.findTask(TENANT, 10L)).thenReturn(Optional.of(row));
        when(adjustmentRepository.updateTask(any(), anyLong(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(adjustmentRepository.insertSplitTask(eq(TENANT), any(NewSplitTask.class), any()))
                .thenReturn(101L);
        when(adjustmentRepository.insertAdjustment(any(), anyLong(), any(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(77L);

        TaskAdjustResult result = service.adjust(10L,
                command("SPLIT", 1, new BigDecimal("4"), new BigDecimal("6")), KEY);

        assertThat(result.applied()).isTrue();
        assertThat(result.dryRun()).isFalse();
        assertThat(result.adjustmentId()).isEqualTo(77L);
        assertThat(result.createdTaskIds()).containsExactly(101L);
        assertThat(result.removedTaskIds()).isEmpty();
        assertThat(result.task().startTime()).isEqualTo(T0);
        assertThat(result.task().endTime()).isEqualTo(T0.plusMinutes(48));
        assertThat(result.task().version()).isEqualTo(2);
        ArgumentCaptor<NewSplitTask> child = ArgumentCaptor.forClass(NewSplitTask.class);
        verify(adjustmentRepository).insertSplitTask(eq(TENANT), child.capture(), any());
        assertThat(child.getValue().qty()).isEqualByComparingTo("6");
        assertThat(child.getValue().startTime()).isEqualTo(T0.plusMinutes(48));
        assertThat(child.getValue().endTime()).isEqualTo(T0.plusHours(2));
        assertThat(child.getValue().sequenceInResource()).isEqualTo(6);
        assertThat(child.getValue().resourceId()).isEqualTo(R1);
        verify(adjustmentRepository).updateTask(eq(TENANT), eq(10L), eq(1), eq(R1), eq(T0),
                eq(T0.plusMinutes(48)), isNull(), isNull(), eq("PLANNED"), isNull(), isNull(), isNull(),
                eq("[]"), eq(new BigDecimal("4")), isNull());
        ArgumentCaptor<String> afterJson = ArgumentCaptor.forClass(String.class);
        verify(adjustmentRepository).insertAdjustment(eq(TENANT), eq(10L), eq(SANDBOX), eq("SPLIT"),
                anyString(), afterJson.capture(), eq("拆分"), isNull());
        assertThat(afterJson.getValue()).contains("\"createdTaskIds\":[101]");
        verify(auditLogRepository).record(eq(TENANT), eq("TASK_ADJUSTED"), eq("TASK"), eq("10"),
                anyMap(), anyMap());
    }

    @Test
    @DisplayName("SPLIT 多段：3 段数量守恒，新建 2 个子任务且顺序顺延、时间连续不重叠")
    void splitThreeSegmentsKeepsQuantityConserved() {
        AdjustTaskRow row = task(10L, R1, 5, "PLANNED", new BigDecimal("10"), T0, T0.plusHours(2));
        when(adjustmentRepository.findTask(TENANT, 10L)).thenReturn(Optional.of(row));
        when(adjustmentRepository.updateTask(any(), anyLong(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(adjustmentRepository.insertSplitTask(eq(TENANT), any(NewSplitTask.class), any()))
                .thenReturn(101L, 102L);
        when(adjustmentRepository.insertAdjustment(any(), anyLong(), any(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(77L);

        TaskAdjustResult result = service.adjust(10L,
                command("SPLIT", 1, new BigDecimal("2"), new BigDecimal("3"), new BigDecimal("5")), KEY);

        assertThat(result.createdTaskIds()).containsExactly(101L, 102L);
        ArgumentCaptor<NewSplitTask> child = ArgumentCaptor.forClass(NewSplitTask.class);
        verify(adjustmentRepository, org.mockito.Mockito.times(2))
                .insertSplitTask(eq(TENANT), child.capture(), any());
        List<NewSplitTask> children = child.getAllValues();
        assertThat(children.get(0).qty()).isEqualByComparingTo("3");
        assertThat(children.get(0).startTime()).isEqualTo(T0.plusMinutes(24));
        assertThat(children.get(0).endTime()).isEqualTo(T0.plusMinutes(60));
        assertThat(children.get(1).qty()).isEqualByComparingTo("5");
        assertThat(children.get(1).startTime()).isEqualTo(T0.plusMinutes(60));
        assertThat(children.get(1).endTime()).isEqualTo(T0.plusHours(2));
        assertThat(children).extracting(NewSplitTask::sequenceInResource).containsExactly(6, 7);
        verify(adjustmentRepository).shiftSequence(TENANT, SANDBOX, R1, 5, 2);
        assertThat(result.task().endTime()).isEqualTo(T0.plusMinutes(24));
    }

    @Test
    @DisplayName("SPLIT 数量之和不等于源 qty → 42200")
    void splitRejectsQuantityNotConserved() {
        AdjustTaskRow row = task(10L, R1, 5, "PLANNED", new BigDecimal("10"), T0, T0.plusHours(2));
        when(adjustmentRepository.findTask(TENANT, 10L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.adjust(10L,
                command("SPLIT", 1, new BigDecimal("3"), new BigDecimal("6")), KEY))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);
        verify(adjustmentRepository, never()).insertSplitTask(any(), any(), any());
    }

    @Test
    @DisplayName("SPLIT 源任务 qty 为空 → 42200")
    void splitRejectsNullSourceQuantity() {
        AdjustTaskRow row = task(10L, R1, 5, "PLANNED", null, T0, T0.plusHours(2));
        when(adjustmentRepository.findTask(TENANT, 10L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.adjust(10L,
                command("SPLIT", 1, new BigDecimal("4"), new BigDecimal("6")), KEY))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("SPLIT dryRun：返回第一段视图，但不写任务/调整/审计")
    void splitDryRunDoesNotPersist() {
        AdjustTaskRow row = task(10L, R1, 5, "PLANNED", new BigDecimal("10"), T0, T0.plusHours(2));
        when(adjustmentRepository.findTask(TENANT, 10L)).thenReturn(Optional.of(row));
        TaskAdjustCommand command = command("SPLIT", 1, new BigDecimal("4"), new BigDecimal("6"));
        command.setDryRun(true);

        TaskAdjustResult result = service.adjust(10L, command, KEY);

        assertThat(result.applied()).isTrue();
        assertThat(result.dryRun()).isTrue();
        assertThat(result.adjustmentId()).isNull();
        assertThat(result.createdTaskIds()).isEmpty();
        assertThat(result.task().endTime()).isEqualTo(T0.plusMinutes(48));
        verify(adjustmentRepository, never()).updateTask(any(), anyLong(), anyInt(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(adjustmentRepository, never()).insertSplitTask(any(), any(), any());
        verify(adjustmentRepository, never()).insertAdjustment(any(), anyLong(), any(), anyString(),
                anyString(), anyString(), anyString(), any());
        verify(auditLogRepository, never()).record(any(), anyString(), anyString(), anyString(),
                anyMap(), anyMap());
    }

    @Test
    @DisplayName("缺少 Idempotency-Key → 40000，且不查库")
    void adjustRejectsMissingIdempotencyKey() {
        assertThatThrownBy(() -> service.adjust(10L,
                command("SPLIT", 1, new BigDecimal("4"), new BigDecimal("6")), null))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(adjustmentRepository, never()).findTask(any(), anyLong());
    }

    @Test
    @DisplayName("MERGE 合法：同资源同工序连续，数量求和、保留最早开始、其余软删")
    void mergeLegalTasks() {
        Map<Long, AdjustTaskRow> rows = Map.of(
                10L, task(10L, R1, 1, "PLANNED", new BigDecimal("10"), T0, T0.plusHours(1)),
                11L, task(11L, R1, 2, "PLANNED", new BigDecimal("20"), T0.plusHours(1), T0.plusHours(2)),
                12L, task(12L, R1, 3, "PLANNED", new BigDecimal("30"), T0.plusHours(2), T0.plusHours(3)));
        stubRows(rows);
        when(adjustmentRepository.updateTask(any(), anyLong(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(adjustmentRepository.insertAdjustment(any(), anyLong(), any(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(88L);

        TaskAdjustResult result = service.adjust(10L,
                mergeCommand(1, 10L, 11L, 12L), KEY);

        assertThat(result.removedTaskIds()).containsExactly(11L, 12L);
        assertThat(result.createdTaskIds()).isEmpty();
        assertThat(result.task().startTime()).isEqualTo(T0);
        assertThat(result.task().endTime()).isEqualTo(T0.plusHours(3));
        verify(adjustmentRepository).updateTask(eq(TENANT), eq(10L), eq(1), eq(R1), eq(T0),
                eq(T0.plusHours(3)), isNull(), isNull(), eq("PLANNED"), isNull(), isNull(), isNull(),
                eq("[]"), eq(new BigDecimal("60")), isNull());
        verify(adjustmentRepository).softDeleteTask(TENANT, 11L);
        verify(adjustmentRepository).softDeleteTask(TENANT, 12L);
        verify(auditLogRepository).record(eq(TENANT), eq("TASK_ADJUSTED"), eq("TASK"), eq("10"),
                anyMap(), anyMap());
    }

    @Test
    @DisplayName("MERGE 跨资源 → 42201")
    void mergeRejectsCrossResource() {
        stubRows(Map.of(
                10L, task(10L, R1, 1, "PLANNED", new BigDecimal("10"), T0, T0.plusHours(1)),
                11L, task(11L, R2, 2, "PLANNED", new BigDecimal("20"), T0.plusHours(1), T0.plusHours(2))));

        assertThatThrownBy(() -> service.adjust(10L, mergeCommand(1, 10L, 11L), KEY))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ADJUSTMENT_INFEASIBLE);
    }

    @Test
    @DisplayName("MERGE 跨工序 → 42201")
    void mergeRejectsCrossOperation() {
        AdjustTaskRow other = new AdjustTaskRow(11L, ORDER, "O-1", null, null, SANDBOX, 2,
                OPERATION_TEMPLATE, R1, "R1", "[]", null, null, T0.plusHours(1), T0.plusHours(2),
                new BigDecimal("20"), "PLANNED", 2, 1, null, null, null, "[]");
        stubRows(Map.of(
                10L, task(10L, R1, 1, "PLANNED", new BigDecimal("10"), T0, T0.plusHours(1)),
                11L, other));

        assertThatThrownBy(() -> service.adjust(10L, mergeCommand(1, 10L, 11L), KEY))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ADJUSTMENT_INFEASIBLE);
    }

    @Test
    @DisplayName("MERGE 资源内不连续 → 42201")
    void mergeRejectsNonContiguousSequence() {
        stubRows(Map.of(
                10L, task(10L, R1, 1, "PLANNED", new BigDecimal("10"), T0, T0.plusHours(1)),
                11L, task(11L, R1, 3, "PLANNED", new BigDecimal("20"), T0.plusHours(1), T0.plusHours(2))));

        assertThatThrownBy(() -> service.adjust(10L, mergeCommand(1, 10L, 11L), KEY))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ADJUSTMENT_INFEASIBLE);
    }

    @Test
    @DisplayName("MERGE 参与任务 qty 为空 → 42200")
    void mergeRejectsNullQuantity() {
        stubRows(Map.of(
                10L, task(10L, R1, 1, "PLANNED", new BigDecimal("10"), T0, T0.plusHours(1)),
                11L, task(11L, R1, 2, "PLANNED", null, T0.plusHours(1), T0.plusHours(2))));

        assertThatThrownBy(() -> service.adjust(10L, mergeCommand(1, 10L, 11L), KEY))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("MERGE 含在制任务 → 40903")
    void mergeRejectsInProgressTask() {
        stubRows(Map.of(
                10L, task(10L, R1, 1, "PLANNED", new BigDecimal("10"), T0, T0.plusHours(1)),
                11L, task(11L, R1, 2, "IN_PROGRESS", new BigDecimal("20"),
                        T0.plusHours(1), T0.plusHours(2))));

        assertThatThrownBy(() -> service.adjust(10L, mergeCommand(1, 10L, 11L), KEY))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ErrorCode.PUBLISHED_CONFLICT);
    }

    @Test
    @DisplayName("SPLIT 撤销：软删新建子任务，源任务 qty/时间恢复为拆分前")
    void undoSplitRestoresSourceAndRemovesChildren() {
        String beforeJson = """
                {"resourceCode":"R1","status":"PLANNED","startTime":"2026-11-04T08:00:00Z",
                 "endTime":"2026-11-04T10:00:00Z","qty":10,"sequenceInResource":5,
                 "lockedResourceCodes":[]}
                """;
        String afterJson = """
                {"resourceCode":"R1","status":"PLANNED","startTime":"2026-11-04T08:00:00Z",
                 "endTime":"2026-11-04T08:48:00Z","qty":4,"sequenceInResource":5,
                 "createdTaskIds":[101]}
                """;
        AdjustmentRow adjustment = new AdjustmentRow(77L, 10L, SANDBOX, "SPLIT", beforeJson, afterJson,
                "拆分", false, null, T0);
        when(adjustmentRepository.findAdjustment(TENANT, 77L)).thenReturn(Optional.of(adjustment));
        when(adjustmentRepository.findLatestActive(TENANT, 10L)).thenReturn(Optional.of(adjustment));
        AdjustTaskRow row = task(10L, R1, 5, "PLANNED", new BigDecimal("4"),
                T0, T0.plusMinutes(48));
        when(adjustmentRepository.findTask(TENANT, 10L)).thenReturn(Optional.of(row));
        when(adjustmentRepository.findResourceByCode(TENANT, "R1"))
                .thenReturn(Optional.of(new ResourceRef(R1, "R1", null)));
        when(adjustmentRepository.updateTask(any(), anyLong(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        TaskAdjustResult result = service.undo(77L);

        assertThat(result.removedTaskIds()).containsExactly(101L);
        assertThat(result.task().startTime()).isEqualTo(T0);
        assertThat(result.task().endTime()).isEqualTo(T0.plusHours(2));
        verify(adjustmentRepository).softDeleteTask(TENANT, 101L);
        verify(adjustmentRepository).updateTask(eq(TENANT), eq(10L), eq(1), eq(R1), eq(T0),
                eq(T0.plusHours(2)), isNull(), isNull(), eq("PLANNED"), isNull(), isNull(), isNull(),
                eq("[]"), eq(new BigDecimal("10")), eq(5));
        verify(adjustmentRepository).shiftSequence(TENANT, SANDBOX, R1, 5, -1);
        verify(adjustmentRepository).markUndone(TENANT, 77L, true);
    }

    @Test
    @DisplayName("MERGE 撤销：目标恢复原 qty，合并方软删行恢复原数量与时间")
    void undoMergeRestoresRemovedTasks() {
        String beforeJson = """
                {"resourceCode":"R1","status":"PLANNED","startTime":"2026-11-04T08:00:00Z",
                 "endTime":"2026-11-04T10:00:00Z","qty":10,"sequenceInResource":1,
                 "lockedResourceCodes":[],"removedTasks":[
                   {"taskId":11,"qty":20,"startTime":"2026-11-04T10:00:00Z",
                    "endTime":"2026-11-04T11:00:00Z","sequenceInResource":2}]}
                """;
        String afterJson = """
                {"resourceCode":"R1","status":"PLANNED","startTime":"2026-11-04T08:00:00Z",
                 "endTime":"2026-11-04T11:00:00Z","qty":30,"sequenceInResource":1,
                 "removedTaskIds":[11]}
                """;
        AdjustmentRow adjustment = new AdjustmentRow(88L, 10L, SANDBOX, "MERGE", beforeJson, afterJson,
                "合并", false, null, T0);
        when(adjustmentRepository.findAdjustment(TENANT, 88L)).thenReturn(Optional.of(adjustment));
        when(adjustmentRepository.findLatestActive(TENANT, 10L)).thenReturn(Optional.of(adjustment));
        AdjustTaskRow row = task(10L, R1, 1, "PLANNED", new BigDecimal("30"), T0, T0.plusHours(3));
        when(adjustmentRepository.findTask(TENANT, 10L)).thenReturn(Optional.of(row));
        when(adjustmentRepository.findResourceByCode(TENANT, "R1"))
                .thenReturn(Optional.of(new ResourceRef(R1, "R1", null)));
        when(adjustmentRepository.updateTask(any(), anyLong(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        TaskAdjustResult result = service.undo(88L);

        assertThat(result.removedTaskIds()).isEmpty();
        verify(adjustmentRepository).updateTask(eq(TENANT), eq(10L), eq(1), eq(R1), eq(T0),
                eq(T0.plusHours(2)), isNull(), isNull(), eq("PLANNED"), isNull(), isNull(), isNull(),
                eq("[]"), eq(new BigDecimal("10")), eq(1));
        verify(adjustmentRepository).restoreMergedTask(eq(TENANT), eq(11L), eq(new BigDecimal("20")),
                eq(T0.plusHours(2)), eq(T0.plusHours(3)), isNull(), isNull(), eq(2));
        verify(adjustmentRepository).markUndone(TENANT, 88L, true);
    }

    private void stubRows(Map<Long, AdjustTaskRow> rows) {
        LongFunction<Optional<AdjustTaskRow>> lookup = id -> Optional.ofNullable(rows.get(id));
        when(adjustmentRepository.findTask(eq(TENANT), anyLong()))
                .thenAnswer(invocation -> lookup.apply(invocation.getArgument(1)));
    }

    private static TaskAdjustCommand command(String operation, int version, BigDecimal... quantities) {
        TaskAdjustCommand command = new TaskAdjustCommand();
        command.setOperation(operation);
        command.setVersion(version);
        command.setReason("拆分");
        command.setSplitQuantities(List.of(quantities));
        return command;
    }

    private static TaskAdjustCommand mergeCommand(int version, Long... taskIds) {
        TaskAdjustCommand command = new TaskAdjustCommand();
        command.setOperation("MERGE");
        command.setVersion(version);
        command.setReason("合并");
        command.setMergeTaskIds(List.of(taskIds));
        return command;
    }

    private static AdjustTaskRow task(long id, UUID resource, int sequence, String status,
                                     BigDecimal qty, OffsetDateTime start, OffsetDateTime end) {
        return new AdjustTaskRow(id, ORDER, "O-1", null, null, SANDBOX, 1, OPERATION_TEMPLATE,
                resource, resource.equals(R1) ? "R1" : "R2", "[]", null, null, start, end, qty,
                status, sequence, 1, null, null, null, "[]");
    }
}
