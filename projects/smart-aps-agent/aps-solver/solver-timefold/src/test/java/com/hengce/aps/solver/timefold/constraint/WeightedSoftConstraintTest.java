package com.hengce.aps.solver.timefold.constraint;

import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SetupMatrixEntry;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.timefold.TimefoldSolverAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 软约束权重化验证：默认关闭时保持 M1 仅硬约束得分；激活后权重直接改变软得分。
 */
class WeightedSoftConstraintTest {

    @AfterEach
    void tearDown() {
        SoftConstraintWeights.disable();
    }

    @Test
    void disabledSoftConstraintsKeepSoftScoreZero() {
        ScheduleSolution solution = new TimefoldSolverAdapter().solve(frozenSetupPairProblem(), config());

        assertEquals(0L, solution.score().softScore().longValueExact());
        assertEquals(0L, solution.score().hardScore().longValueExact());
    }

    @Test
    void weightsChangeSoftScoreDeterministically() {
        long setupScore = solveSoftScore(weights(ConstraintCodes.MIN_SETUP_TIME));
        long makespanScore = solveSoftScore(weights(ConstraintCodes.MIN_MAKESPAN));

        assertTrue(setupScore < 0, "换型权重应产生负软得分");
        assertTrue(makespanScore < 0, "总完工时间权重应产生负软得分");
        assertNotEquals(setupScore, makespanScore, "不同权重应改变软得分");
    }

    @Test
    void rejectsWeightSetThatDoesNotSumToHundred() {
        Map<String, Integer> invalid = weights(ConstraintCodes.MIN_TARDINESS);
        invalid.put(ConstraintCodes.EDD, 15);

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> SoftConstraintWeights.activate(invalid));
        assertEquals("软约束权重之和必须为 100，实际为 115", exception.getMessage());
    }

    private long solveSoftScore(Map<String, Integer> weights) {
        ScheduleSolution solution = new TimefoldSolverAdapter().solve(
                frozenSetupPairProblem(), configWithWeights(weights));
        return solution.score().softScore().longValueExact();
    }

    /**
     * 权重必须随请求进入 {@code SolveConfig.parameters}：适配器在未携带 {@code softWeights} 时
     * 会显式 disable 进程级注册表，直接 {@code activate()} 再求解的旧写法会被清空。
     */
    private static SolveConfig configWithWeights(Map<String, Integer> weights) {
        return new SolveConfig("timefold", 1, 1, "LATE_ACCEPTANCE", 1L, 0L, 0,
                "FORWARD", true, Map.<String, Object>of("softWeights", weights));
    }

    private static Map<String, Integer> weights(String enabledCode) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (String code : ConstraintCodes.SOFT_RULE_CODES) {
            weights.put(code, code.equals(enabledCode) ? 100 : 0);
        }
        return weights;
    }

    /**
     * 同一主资源上的一对任务：OP-05（前序，06:00–08:00）与 OP-10（08:00 起）。
     *
     * <p>两个任务的静态换型都设为 0，换型只可能来自矩阵；矩阵对该资源上的相邻对双向都配
     * 1800 秒，因此无论求解器把哪一个排在前面，后一个任务都会真实产生 1800 秒换型
     * （30 分钟），换型权重激活后软得分必为负且与总完工时间得分不同。</p>
     */
    private static ScheduleProblem frozenSetupPairProblem() {
        UUID primaryResourceId = UUID.randomUUID();
        SchedulingResource primary = resource(primaryResourceId, "R-CNC");
        LocalDateTime upstreamStart = LocalDateTime.of(2026, 1, 5, 6, 0);
        LocalDateTime pinnedStart = LocalDateTime.of(2026, 1, 5, 8, 0);

        TaskAssignment upstream = new TaskAssignment(
                null,
                UUID.randomUUID(),
                "SO-UPSTREAM",
                5,
                "工序 5",
                null,
                primaryResourceId,
                "R-CNC",
                List.of(),
                upstreamStart.atOffset(ZoneOffset.UTC),
                upstreamStart.atOffset(ZoneOffset.UTC),
                upstreamStart.atOffset(ZoneOffset.UTC),
                upstreamStart.atOffset(ZoneOffset.UTC).plusSeconds(7200),
                BigDecimal.valueOf(12),
                0,
                "FROZEN",
                true,
                1);
        SchedulingOrder upstreamOrder = new SchedulingOrder(
                upstream.orderId(),
                upstream.orderNo(),
                "MAT-SO-UPSTREAM",
                BigDecimal.valueOf(12),
                LocalDate.of(2026, 1, 10),
                "NORMAL",
                "FROZEN",
                UUID.randomUUID(),
                List.of(new SchedulingOperation(5, "OP-05", "工序 5", null,
                        BigDecimal.valueOf(600), BigDecimal.ZERO, primaryResourceId,
                        null, List.of(), true)));
        TaskAssignment frozen = new TaskAssignment(
                null,
                UUID.randomUUID(),
                "SO-FROZEN",
                10,
                "工序 10",
                null,
                primaryResourceId,
                "R-CNC",
                List.of(),
                pinnedStart.atOffset(ZoneOffset.UTC),
                pinnedStart.atOffset(ZoneOffset.UTC),
                pinnedStart.atOffset(ZoneOffset.UTC),
                pinnedStart.atOffset(ZoneOffset.UTC).plusSeconds(7200),
                BigDecimal.valueOf(12),
                0,
                "FROZEN",
                true,
                1);
        SchedulingOrder order = new SchedulingOrder(
                frozen.orderId(),
                frozen.orderNo(),
                "MAT-SO-FROZEN",
                BigDecimal.valueOf(12),
                LocalDate.of(2026, 1, 10),
                "NORMAL",
                "FROZEN",
                UUID.randomUUID(),
                List.of(new SchedulingOperation(10, "OP-10", "工序 10", null,
                        BigDecimal.valueOf(600), BigDecimal.ZERO, primaryResourceId,
                        null, List.of(), true)));
        List<SetupMatrixEntry> setupMatrix = List.of(
                new SetupMatrixEntry(primaryResourceId, "OPERATION_CODE", "OP-05", "OP-10", 1800),
                new SetupMatrixEntry(primaryResourceId, "OPERATION_CODE", "OP-10", "OP-05", 1800));
        return new ScheduleProblem(
                "tenant-demo",
                ScheduleProblem.DEFAULT_SANDBOX_ID,
                null,
                null,
                "FORWARD",
                List.of(upstreamOrder, order),
                List.of(primary),
                List.of(upstream, frozen),
                List.of(),
                setupMatrix);
    }

    private static SolveConfig config() {
        return new SolveConfig("timefold", 1, 1, "LATE_ACCEPTANCE", 1L, 0L, 0,
                "FORWARD", true, Map.of());
    }

    private static SchedulingResource resource(UUID resourceId, String code) {
        return new SchedulingResource(resourceId, code, "PRIMARY", null, "SECONDS_PER_UNIT",
                BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE");
    }
}
