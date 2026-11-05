package com.hengce.aps.solver.timefold;

import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.timefold.constraint.SoftConstraintWeights;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 软约束权重端到端对比（真跑 Timefold，不用 mock）。
 *
 * <p>同一道题、同一份数据：单笔冻结任务固定在 2026-01-05 08:00、加工 30 分钟，
 * 订单交期 2026-01-04（截止 23:59:59），因此超期恒为 510 分钟；同资源仅此一个任务，
 * EDD/SPT/同品连续/优先级尊重均无相邻对（贡献 0），换型为 0，负载小时平方为 0。</p>
 *
 * <p>两次求解的唯一输入差异是 {@code softWeights} 权重向量（合计必须 100，因此
 * 提高 {@code MIN_TARDINESS} 0 → 40 时由其余四条各下调 10 补齐）：</p>
 * <ul>
 *   <li>权重 0：软得分 = {@code -MIN_MAKESPAN(16) * makespan(30)} = {@code -480}；</li>
 *   <li>权重 40：= {@code -(40 * tardiness(510) + 6 * makespan(30))} = {@code -20580}；</li>
 *   <li>差值 = {@code -(40 * 510 + (6 - 16) * 30)} = {@code -20100}，与实测差额逐分吻合。</li>
 * </ul>
 *
 * <p>断言不写死“差了个大数就算过”：tardiness 与 makespan 都从本次解的任务时间现算，
 * 再代入各规则权重。</p>
 */
class SoftWeightsEndToEndTest {

    private static final UUID RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000005a1");

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000005b1");

    private static final LocalDateTime PINNED_START = LocalDateTime.of(2026, 1, 5, 8, 0);

    private static final LocalDate DUE_DATE = LocalDate.of(2026, 1, 4);

    private static final LocalTime DUE_TIME = LocalTime.of(23, 59, 59);

    @AfterEach
    void tearDown() {
        SoftConstraintWeights.disable();
    }

    /**
     * 同一题面只翻权重向量：得分必须满足“逾期分钟 × 权重 + makespan × 权重”的算术。
     */
    @Test
    void weightDifferenceMustMatchTardinessAndMakespanArithmetic() {
        ScheduleSolution baseline = solve(weights(0));
        ScheduleSolution weighted = solve(weights(40));

        long baselineSoft = baseline.score().softScore().longValueExact();
        long weightedSoft = weighted.score().softScore().longValueExact();

        long tardiness = tardinessMinutes(weighted);
        long makespan = makespanMinutes(weighted);
        assertEquals(510L, tardiness, "冻结任务结束时间比交期晚 510 分钟");
        assertEquals(30L, makespan, "单任务 makespan 为 30 分钟");

        assertEquals(-16L * makespan, baselineSoft,
                "权重 0 时软得分只应剩 -MIN_MAKESPAN(16) * makespan");
        assertEquals(-(40L * tardiness + 6L * makespan), weightedSoft,
                "权重 40 时软得分 = -(40 * 逾期分钟 + 6 * makespan)");
        assertEquals(-(40L * tardiness + (6L - 16L) * makespan), weightedSoft - baselineSoft,
                "差值必须与 逾期分钟 × 权重变化 + makespan × 权重变化 吻合");
        assertTrue(weightedSoft < baselineSoft, "提高超期权重后软得分必须更负");
        assertFalse(SoftConstraintWeights.isEnabled(), "求解结束必须 disable()，避免污染其他任务");
    }

    /**
     * 同题面重复执行两次：得分必须稳定，证明用例可重复而非一次性碰巧。
     */
    @Test
    void repeatedSolveShouldProduceSameScores() {
        long baselineFirst = solve(weights(0)).score().softScore().longValueExact();
        long baselineSecond = solve(weights(0)).score().softScore().longValueExact();
        long weightedFirst = solve(weights(40)).score().softScore().longValueExact();
        long weightedSecond = solve(weights(40)).score().softScore().longValueExact();

        assertEquals(baselineFirst, baselineSecond, "同一题面重复求解得分必须稳定: 权重 0");
        assertEquals(weightedFirst, weightedSecond, "同一题面重复求解得分必须稳定: 权重 40");
        assertEquals(-480L, baselineFirst);
        assertEquals(-20580L, weightedFirst);
    }

    /**
     * 不传 softWeights 必须显式回到 M1：即使注册表里残留了上一次的权重，也不能被静默沿用。
     * 若默认权重被悄悄注入，本题的 510 分钟逾期会产生负分，因此 {@code softScore==0} 就是“8 条未注入”的证据。
     */
    @Test
    void omittedWeightsShouldForceM1HardOnlyBaseline() {
        SoftConstraintWeights.activate(SoftConstraintWeights.defaults());

        ScheduleSolution solution = new TimefoldSolverAdapter()
                .solve(problem(), config(Map.of()));

        assertEquals("SOLVED", solution.status());
        assertEquals(0L, solution.score().hardScore().longValueExact());
        assertEquals(0L, solution.score().softScore().longValueExact(),
                "未提供 softWeights 时必须回到 M1 仅硬约束；若默认权重生效，逾期 510 分钟会产生负分");
        assertFalse(SoftConstraintWeights.isEnabled(), "求解结束必须回到关闭状态");
    }

    /**
     * 畸形权重集（8 条齐全但合计 99）不能悄悄生效：求解必须失败并给出合计错误，
     * 不得退回默认权重继续算。
     */
    @Test
    void invalidWeightTotalShouldFailInsteadOfBeingApplied() {
        Map<String, Integer> weights = weights(40);
        weights.put("MIN_MAKESPAN", 5);

        ScheduleSolution solution = new TimefoldSolverAdapter()
                .solve(problem(), config(Map.of("softWeights", weights)));

        assertEquals("FAILED", solution.status());
        assertTrue(solution.message().contains("软约束权重之和必须为 100，实际为 99"),
                "必须明确报出合计错误，而不是静默生效: " + solution.message());
        assertFalse(SoftConstraintWeights.isEnabled(), "非法权重不能留在注册表");
    }

    private ScheduleSolution solve(Map<String, Integer> weights) {
        ScheduleSolution solution = new TimefoldSolverAdapter()
                .solve(problem(), config(Map.of("softWeights", weights)));
        assertEquals("SOLVED", solution.status(), "冻结任务无可行解移动，必须稳定可行");
        assertEquals(0L, solution.score().hardScore().longValueExact());
        return solution;
    }

    /**
     * 从本次解的任务时间现算逾期分钟数。
     */
    private static long tardinessMinutes(ScheduleSolution solution) {
        TaskAssignment task = solution.assignments().get(0);
        LocalDateTime deadline = DUE_DATE.atTime(DUE_TIME);
        return Duration.between(deadline, task.endTime().toLocalDateTime()).toMinutes();
    }

    /**
     * 从本次解的任务时间现算 makespan 分钟数。
     */
    private static long makespanMinutes(ScheduleSolution solution) {
        LocalDateTime earliest = solution.assignments().stream()
                .map(task -> task.startTime().toLocalDateTime())
                .min(LocalDateTime::compareTo)
                .orElseThrow();
        LocalDateTime latest = solution.assignments().stream()
                .map(task -> task.endTime().toLocalDateTime())
                .max(LocalDateTime::compareTo)
                .orElseThrow();
        return Duration.between(earliest, latest).toMinutes();
    }

    /**
     * 权重：{@code MIN_TARDINESS=0} 时 EDD16/SPT8/同品12/换型12/负载12/优先级24/完工16（合计 100）；
     * {@code MIN_TARDINESS=40} 时由 EDD、负载、优先级、完工四条各下调 10 腾出 40，合计仍为 100。
     */
    private static Map<String, Integer> weights(int tardiness) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("MIN_TARDINESS", tardiness);
        if (tardiness == 0) {
            weights.put("EDD", 16);
            weights.put("SPT", 8);
            weights.put("SAME_PRODUCT_CONTINUOUS", 12);
            weights.put("MIN_SETUP_TIME", 12);
            weights.put("BALANCE_LOAD", 12);
            weights.put("PRIORITY_RESPECT", 24);
            weights.put("MIN_MAKESPAN", 16);
        } else {
            weights.put("EDD", 6);
            weights.put("SPT", 8);
            weights.put("SAME_PRODUCT_CONTINUOUS", 12);
            weights.put("MIN_SETUP_TIME", 12);
            weights.put("BALANCE_LOAD", 2);
            weights.put("PRIORITY_RESPECT", 14);
            weights.put("MIN_MAKESPAN", 6);
        }
        return weights;
    }

    private static ScheduleProblem problem() {
        SchedulingResource resource = new SchedulingResource(
                RESOURCE_ID, "R-E2E", "PRIMARY", null, "SECONDS_PER_UNIT",
                BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE");
        SchedulingOperation operation = new SchedulingOperation(
                10, "OP-10", "工序 10", null, BigDecimal.valueOf(1800), BigDecimal.ZERO,
                RESOURCE_ID, null, List.of(), false);
        SchedulingOrder order = new SchedulingOrder(
                ORDER_ID, "SO-E2E", "MAT-E2E", "FAM-E2E", BigDecimal.ONE,
                DUE_DATE, "NORMAL", "FROZEN", UUID.randomUUID(), List.of(operation));
        OffsetDateTime start = PINNED_START.atOffset(ZoneOffset.UTC);
        TaskAssignment frozen = new TaskAssignment(
                null, ORDER_ID, "SO-E2E", 10, "工序 10", null,
                RESOURCE_ID, "R-E2E", List.of(),
                start, start, start, start.plusSeconds(1800),
                BigDecimal.ONE, 0, "FROZEN", true, 1);
        return new ScheduleProblem(
                "tenant-e2e", ScheduleProblem.DEFAULT_SANDBOX_ID, null, null, "FORWARD",
                List.of(order), List.of(resource), List.of(frozen), List.of());
    }

    private static SolveConfig config(Map<String, Object> parameters) {
        return new SolveConfig("timefold", 1, 1, "LATE_ACCEPTANCE", 1L, 0L, 0,
                "FORWARD", true, parameters);
    }
}
