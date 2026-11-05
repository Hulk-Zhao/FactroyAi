package com.hengce.aps.solver.timefold;

import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.TaskAssignment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约 C19 §24.1.1 的硬断言：{@code sum(justification.deduction)} 必须精确等于
 * {@code -bestScore.softScore}。
 *
 * <p>题面与 {@code SoftWeightsEndToEndTest} 一致：单笔冻结任务固定在 2026-01-05 08:00、
 * 加工 30 分钟，交期 2026-01-04，超期恒为 510 分钟；因此软约束仅 MIN_TARDINESS 与
 * MIN_MAKESPAN 有非零扣分。权重取 {@code MIN_TARDINESS=90, MIN_MAKESPAN=10}（合计 100），
 * 期望扣分分别为 90×510 与 10×30。断言从本次解的实际扣分重新求和，不写死数字。</p>
 */
class JustificationScoreConsistencyTest {

    private static final UUID RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000006a1");

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000006b1");

    private static final LocalDateTime PINNED_START = LocalDateTime.of(2026, 1, 5, 8, 0);

    private static final LocalDate DUE_DATE = LocalDate.of(2026, 1, 4);

    private static final LocalTime DUE_TIME = LocalTime.of(23, 59, 59);

    @Test
    void sumOfJustificationDeductionsMustEqualNegatedSoftScore() {
        ScheduleSolution solution = solve(weights());

        assertEquals("SOLVED", solution.status(), "冻结任务无可行解移动，必须稳定可行");
        assertEquals(0L, solution.score().hardScore().longValueExact(), "本题面不应产生硬约束违反");

        List<ConstraintJustification> softRows = solution.justifications().stream()
                .filter(row -> "SOFT".equals(row.type()))
                .toList();
        assertFalse(softRows.isEmpty(), "启用软约束后必须产出软约束归因行");
        assertTrue(softRows.size() >= 2, "超期与完工时间两条软约束都应有扣分: " + softRows.size());

        BigDecimal sum = softRows.stream()
                .map(ConstraintJustification::deduction)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal negatedSoftScore = solution.score().softScore().negate();
        assertEquals(0, sum.compareTo(negatedSoftScore),
                "sum(deduction) 必须精确等于 -softScore: sum=" + sum
                        + " softScore=" + solution.score().softScore());
        assertFalse(com.hengce.aps.solver.timefold.constraint.SoftConstraintWeights.isEnabled(),
                "求解结束必须 disable()，避免权重污染其他用例");
    }

    private static ScheduleSolution solve(Map<String, Integer> weights) {
        return new TimefoldSolverAdapter()
                .solve(problem(), config(Map.of("softWeights", weights)));
    }

    /**
     * 权重合计 100：超期 90、完工时间 10，其余 6 条为 0。
     */
    private static Map<String, Integer> weights() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("MIN_TARDINESS", 90);
        weights.put("EDD", 0);
        weights.put("SPT", 0);
        weights.put("SAME_PRODUCT_CONTINUOUS", 0);
        weights.put("MIN_SETUP_TIME", 0);
        weights.put("BALANCE_LOAD", 0);
        weights.put("PRIORITY_RESPECT", 0);
        weights.put("MIN_MAKESPAN", 10);
        return weights;
    }

    private static ScheduleProblem problem() {
        SchedulingResource resource = new SchedulingResource(
                RESOURCE_ID, "R-JUST", "PRIMARY", null, "SECONDS_PER_UNIT",
                BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE");
        SchedulingOperation operation = new SchedulingOperation(
                10, "OP-10", "工序 10", null, BigDecimal.valueOf(1800), BigDecimal.ZERO,
                RESOURCE_ID, null, List.of(), false);
        SchedulingOrder order = new SchedulingOrder(
                ORDER_ID, "SO-JUST", "MAT-JUST", "FAM-JUST", BigDecimal.ONE,
                DUE_DATE, "NORMAL", "FROZEN", UUID.randomUUID(), List.of(operation));
        OffsetDateTime start = PINNED_START.atOffset(ZoneOffset.UTC);
        TaskAssignment frozen = new TaskAssignment(
                null, ORDER_ID, "SO-JUST", 10, "工序 10", null,
                RESOURCE_ID, "R-JUST", List.of(),
                start, start, start, start.plusSeconds(1800),
                BigDecimal.ONE, 0, "FROZEN", true, 1);
        return new ScheduleProblem(
                "tenant-just", ScheduleProblem.DEFAULT_SANDBOX_ID, null, null, "FORWARD",
                List.of(order), List.of(resource), List.of(frozen), List.of());
    }

    private static SolveConfig config(Map<String, Object> parameters) {
        return new SolveConfig("timefold", 1, 1, "LATE_ACCEPTANCE", 1L, 0L, 0,
                "FORWARD", true, parameters);
    }
}
