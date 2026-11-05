package com.hengce.aps.service.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.hengce.aps.infrastructure.persistence.sandbox.SandboxRepository.CompareTaskRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C32 compare 纯计算单测：方向 id - targetId，计数全量、明细截断，交期截止点按业务时区。
 */
class SandboxComparisonCalculatorTest {

    private static final UUID COMPARED_ID = UUID.fromString("00000000-0000-0000-0000-00000000c032");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-00000000b032");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    @DisplayName("added/removed/moved 三种计数与变更类型")
    void shouldCountAddedRemovedAndMoved() {
        List<CompareTaskRow> compared = new ArrayList<>(List.of(
                task(1L, "SO-A", 10, utc(2026, 11, 5, 8, 0), utc(2026, 11, 5, 9, 0), "R1",
                        LocalDate.of(2026, 11, 20), false),
                task(2L, "SO-B", 10, utc(2026, 11, 5, 9, 30), utc(2026, 11, 5, 10, 30), "R2",
                        LocalDate.of(2026, 11, 20), false),
                task(3L, "SO-C", 10, utc(2026, 11, 5, 11, 0), utc(2026, 11, 5, 12, 0), "R3",
                        LocalDate.of(2026, 11, 20), false)));
        List<CompareTaskRow> target = List.of(
                task(11L, "SO-A", 10, utc(2026, 11, 5, 8, 0), utc(2026, 11, 5, 9, 0), "R1",
                        LocalDate.of(2026, 11, 20), false),
                task(12L, "SO-B", 10, utc(2026, 11, 5, 9, 0), utc(2026, 11, 5, 10, 0), "R2",
                        LocalDate.of(2026, 11, 20), false),
                task(13L, "SO-D", 10, utc(2026, 11, 5, 13, 0), utc(2026, 11, 5, 14, 0), "R4",
                        LocalDate.of(2026, 11, 20), false));

        SandboxCompareView view = SandboxComparisonCalculator.compare(COMPARED_ID, TARGET_ID,
                compared, target, SHANGHAI);

        assertThat(view.sandboxId()).isEqualTo(COMPARED_ID);
        assertThat(view.targetId()).isEqualTo(TARGET_ID);
        assertThat(view.addedTaskCount()).isEqualTo(1);
        assertThat(view.removedTaskCount()).isEqualTo(1);
        assertThat(view.movedTaskCount()).isEqualTo(1);
        assertThat(view.changedTasks()).extracting(SandboxCompareView.ChangedTaskView::changeType)
                .containsExactly("ADDED", "MOVED", "REMOVED");
        assertThat(view.truncated()).isFalse();
    }

    @Test
    @DisplayName("变更明细超过 200 条时截断，计数仍为全量")
    void shouldTruncateChangedTasksButKeepFullCounts() {
        List<CompareTaskRow> compared = new ArrayList<>();
        for (int index = 0; index < 250; index++) {
            compared.add(task(1000L + index, "SO-T" + index, 10,
                    utc(2026, 11, 5, 8, 0), utc(2026, 11, 5, 9, 0), "R1",
                    LocalDate.of(2026, 11, 20), false));
        }

        SandboxCompareView view = SandboxComparisonCalculator.compare(COMPARED_ID, TARGET_ID,
                compared, List.of(), SHANGHAI);

        assertThat(view.addedTaskCount()).isEqualTo(250);
        assertThat(view.changedTasks()).hasSize(SandboxComparisonCalculator.CHANGED_TASK_LIMIT);
        assertThat(view.truncated()).isTrue();
    }

    @Test
    @DisplayName("交期分钟差、利用率均值差、换型次数差按方向 id - targetId")
    void shouldComputeDeltasInFixedDirection() {
        CompareTaskRow comparedTask = task(21L, "SO-E", 10,
                utc(2026, 11, 5, 9, 0), utc(2026, 11, 5, 10, 0), "R1",
                LocalDate.of(2026, 11, 5), true);
        CompareTaskRow targetTask = task(22L, "SO-E", 10,
                utc(2026, 11, 5, 9, 0), utc(2026, 11, 5, 9, 30), "R1",
                LocalDate.of(2026, 11, 5), false);

        SandboxCompareView view = SandboxComparisonCalculator.compare(COMPARED_ID, TARGET_ID,
                List.of(comparedTask), List.of(targetTask), SHANGHAI);

        // 截止点为业务时区 Asia/Shanghai 的 2026-11-06 00:00（= 11-05 16:00Z）：
        // 10:00Z -> -360 分钟，9:30Z -> -390 分钟，差 +30
        assertThat(view.dueDateDeltaMinutes()).isEqualTo(30L);
        // 窗口 09:00–10:00 共 60 分钟，占用 60 分钟 => 1.0；基线 30/30 => 1.0
        assertThat(view.utilizationDelta()).isEqualTo(0.0);
        assertThat(view.setupCountDelta()).isEqualTo(1);
        assertThat(view.movedTaskCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("交期当日业务时区 23:59:59 完成：不算逾期，差值 0")
    void shouldTreatCompletionJustBeforeMidnightAsNotOverdue() {
        CompareTaskRow comparedTask = task(31L, "SO-T1", 10,
                local(2026, 11, 5, 12, 0, 0), local(2026, 11, 5, 23, 59, 59), "R1",
                LocalDate.of(2026, 11, 5), false);
        CompareTaskRow targetTask = task(32L, "SO-T1", 10,
                local(2026, 11, 5, 12, 0, 0), cutoff(LocalDate.of(2026, 11, 5)), "R1",
                LocalDate.of(2026, 11, 5), false);

        SandboxCompareView view = SandboxComparisonCalculator.compare(COMPARED_ID, TARGET_ID,
                List.of(comparedTask), List.of(targetTask), SHANGHAI);

        assertThat(view.dueDateDeltaMinutes()).isZero();
    }

    @Test
    @DisplayName("本地次日 07:00 完成：+420 分钟（按 UTC 贴偏移会误算为 0）")
    void shouldCountSevenAmNextDayAsOvertime() {
        CompareTaskRow comparedTask = task(33L, "SO-T2", 10,
                local(2026, 11, 6, 3, 0, 0), local(2026, 11, 6, 7, 0, 0), "R1",
                LocalDate.of(2026, 11, 5), false);
        CompareTaskRow targetTask = task(34L, "SO-T2", 10,
                local(2026, 11, 5, 12, 0, 0), cutoff(LocalDate.of(2026, 11, 5)), "R1",
                LocalDate.of(2026, 11, 5), false);

        SandboxCompareView view = SandboxComparisonCalculator.compare(COMPARED_ID, TARGET_ID,
                List.of(comparedTask), List.of(targetTask), SHANGHAI);

        assertThat(view.dueDateDeltaMinutes()).isEqualTo(420L);
    }

    @Test
    @DisplayName("本地次日 09:00 完成：+540 分钟")
    void shouldCountNineAmNextDayAsOvertime() {
        CompareTaskRow comparedTask = task(35L, "SO-T3", 10,
                local(2026, 11, 6, 4, 0, 0), local(2026, 11, 6, 9, 0, 0), "R1",
                LocalDate.of(2026, 11, 5), false);
        CompareTaskRow targetTask = task(36L, "SO-T3", 10,
                local(2026, 11, 5, 12, 0, 0), cutoff(LocalDate.of(2026, 11, 5)), "R1",
                LocalDate.of(2026, 11, 5), false);

        SandboxCompareView view = SandboxComparisonCalculator.compare(COMPARED_ID, TARGET_ID,
                List.of(comparedTask), List.of(targetTask), SHANGHAI);

        assertThat(view.dueDateDeltaMinutes()).isEqualTo(540L);
    }

    @Test
    @DisplayName("endTime 或 dueDate 为 null 的任务跳过，不参与 max")
    void shouldSkipTasksWithMissingEndOrDueDate() {
        CompareTaskRow noDueDate = task(37L, "SO-T4-NODUE", 10,
                local(2026, 11, 5, 12, 0, 0), local(2026, 12, 31, 0, 0, 0), "R1", null, false);
        CompareTaskRow noEnd = task(38L, "SO-T4-NOEND", 10,
                local(2026, 11, 5, 12, 0, 0), null, "R1", LocalDate.of(2026, 11, 5), false);
        CompareTaskRow valid = task(39L, "SO-T4", 10,
                local(2026, 11, 5, 12, 0, 0), local(2026, 11, 5, 23, 59, 59), "R1",
                LocalDate.of(2026, 11, 5), false);
        CompareTaskRow targetTask = task(40L, "SO-T4", 10,
                local(2026, 11, 5, 12, 0, 0), cutoff(LocalDate.of(2026, 11, 5)), "R1",
                LocalDate.of(2026, 11, 5), false);

        SandboxCompareView view = SandboxComparisonCalculator.compare(COMPARED_ID, TARGET_ID,
                List.of(noDueDate, noEnd, valid), List.of(targetTask), SHANGHAI);

        // 若无 dueDate 任务未被跳过，12-31 完成会带来数万分钟的正差值
        assertThat(view.dueDateDeltaMinutes()).isZero();
    }

    @Test
    @DisplayName("两侧都无合格任务：差值 0")
    void shouldReturnZeroWhenNoQualifyingTaskOnEitherSide() {
        CompareTaskRow noDueDate = task(41L, "SO-T5", 10,
                local(2026, 11, 5, 12, 0, 0), local(2026, 11, 6, 7, 0, 0), "R1", null, false);
        CompareTaskRow noEnd = task(42L, "SO-T5-B", 10,
                local(2026, 11, 5, 12, 0, 0), null, "R1", LocalDate.of(2026, 11, 5), false);

        SandboxCompareView view = SandboxComparisonCalculator.compare(COMPARED_ID, TARGET_ID,
                List.of(noDueDate, noEnd), List.of(noDueDate), SHANGHAI);

        assertThat(view.dueDateDeltaMinutes()).isZero();
    }

    @Test
    @DisplayName("业务时区切到 UTC 后同一数据的差值随之改变（读配置而非常量）")
    void shouldFollowConfiguredBusinessZone() {
        CompareTaskRow comparedTask = task(43L, "SO-T6", 10,
                local(2026, 11, 5, 19, 0, 0), local(2026, 11, 6, 7, 0, 0), "R1",
                LocalDate.of(2026, 11, 5), false);
        CompareTaskRow targetTask = task(44L, "SO-T6-NODUE", 10,
                local(2026, 11, 5, 19, 0, 0), local(2026, 11, 6, 7, 0, 0), "R1", null, false);

        SandboxCompareView shanghai = SandboxComparisonCalculator.compare(COMPARED_ID, TARGET_ID,
                List.of(comparedTask), List.of(targetTask), SHANGHAI);
        SandboxCompareView utc = SandboxComparisonCalculator.compare(COMPARED_ID, TARGET_ID,
                List.of(comparedTask), List.of(targetTask), UTC);

        // 本地次日 07:00 = 2026-11-05T23:00Z；上海截止点 11-05 16:00Z -> +420；UTC 截止点 11-06 00:00Z -> -60
        assertThat(shanghai.dueDateDeltaMinutes()).isEqualTo(420L);
        assertThat(utc.dueDateDeltaMinutes()).isEqualTo(-60L);
    }

    private static CompareTaskRow task(long taskId, String orderNo, int operationIndex,
                                       OffsetDateTime start, OffsetDateTime end, String resourceCode,
                                       LocalDate dueDate, boolean setup) {
        return new CompareTaskRow(taskId, orderNo, operationIndex, start, end, resourceCode, dueDate,
                setup);
    }

    private static OffsetDateTime utc(int year, int month, int day, int hour, int minute) {
        return OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC);
    }

    private static OffsetDateTime local(int year, int month, int day, int hour, int minute,
                                        int second) {
        return OffsetDateTime.of(year, month, day, hour, minute, second, 0,
                SHANGHAI.getRules().getOffset(java.time.LocalDateTime.of(year, month, day, hour, minute)));
    }

    private static OffsetDateTime cutoff(LocalDate dueDate) {
        return dueDate.plusDays(1).atStartOfDay(SHANGHAI).toOffsetDateTime();
    }
}
