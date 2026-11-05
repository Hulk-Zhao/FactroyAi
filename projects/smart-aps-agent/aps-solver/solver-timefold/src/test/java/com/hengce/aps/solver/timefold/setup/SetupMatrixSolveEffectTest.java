package com.hengce.aps.solver.timefold.setup;

import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SetupMatrixEntry;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.timefold.TimefoldSolverAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C44 求解侧验收：同一批任务，换型矩阵 30 分钟与无换型相比，
 * 真实 Timefold 求解后任务的 {@code startTime}（落库 {@code task.start_time}）被推迟 1200 秒。
 *
 * <p>两个订单、同一资源、各一道工序；A→B 与 B→A 都配置 1800 秒，
 * 因此无论求解器把哪单排在资源列表后位，命中矩阵的任务换型均为 1800 秒。
 * 无矩阵时全部回退静态 600 秒。</p>
 */
class SetupMatrixSolveEffectTest {

    private static final int SOLVE_SECONDS = 2;

    private static final int STATIC_SETUP_SECONDS = 600;

    private static final int MATRIX_SETUP_SECONDS = 1800;

    @Test
    void matrixSetupDelaysProcessingStart() {
        UUID resourceId = UUID.randomUUID();
        SchedulingResource resource = new SchedulingResource(
                resourceId, "R-CNC", "PRIMARY", null, "SECONDS_PER_UNIT",
                BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE", List.of());
        List<SchedulingOrder> orders = List.of(
                order("SO-A", "MAT-A", "FAM-A", resourceId),
                order("SO-B", "MAT-B", "FAM-B", resourceId));

        ScheduleSolution withoutMatrix = solve(orders, resource, List.of());
        ScheduleSolution withMatrix = solve(orders, resource, List.of(
                new SetupMatrixEntry(null, null, "MATERIAL_CODE", "MAT-A", "MAT-B",
                        MATRIX_SETUP_SECONDS, null, null),
                new SetupMatrixEntry(null, null, "MATERIAL_CODE", "MAT-B", "MAT-A",
                        MATRIX_SETUP_SECONDS, null, null)));

        assertEquals("SOLVED", withoutMatrix.status(), withoutMatrix.message());
        assertEquals("SOLVED", withMatrix.status(), withMatrix.message());
        assertEquals(2, withoutMatrix.assignments().size());
        assertEquals(2, withMatrix.assignments().size());
        assertEquals(Boolean.TRUE,
                withoutMatrix.metrics().extra().get("setupMatrixFallback"),
                "无矩阵时应标记 setupMatrixFallback=true");
        assertEquals(Boolean.FALSE,
                withMatrix.metrics().extra().get("setupMatrixFallback"),
                "有矩阵时应标记 setupMatrixFallback=false，证明矩阵已进入求解");

        TaskAssignment matrixTask = withMatrix.assignments().stream()
                .max(Comparator.comparing(SetupMatrixSolveEffectTest::setupSeconds))
                .orElseThrow();
        TaskAssignment baselineTask = withoutMatrix.assignments().stream()
                .filter(assignment -> assignment.orderNo().equals(matrixTask.orderNo()))
                .findFirst()
                .orElseThrow();

        assertEquals(MATRIX_SETUP_SECONDS, setupSeconds(matrixTask),
                "命中矩阵的任务换型应为 1800 秒");
        assertEquals(STATIC_SETUP_SECONDS, setupSeconds(baselineTask),
                "无矩阵时同一任务应回退静态 600 秒");
        assertEquals(matrixTask.startTime(), matrixTask.setupStartTime()
                .plusSeconds(MATRIX_SETUP_SECONDS), "有矩阵时加工开始 = 占用开始 + 1800");
        assertEquals(baselineTask.startTime(), baselineTask.setupStartTime()
                .plusSeconds(STATIC_SETUP_SECONDS), "无矩阵时加工开始 = 占用开始 + 600");
        assertEquals(1200L, setupSeconds(matrixTask) - setupSeconds(baselineTask),
                "换型矩阵使加工开始相对占用开始推迟 1200 秒");
        assertTrue(matrixTask.startTime().isAfter(baselineTask.startTime())
                        || matrixTask.startTime().equals(baselineTask.startTime()),
                "同一任务有矩阵时加工开始不应早于无矩阵");

        long observedDelay = Duration.between(
                baselineTask.startTime(), matrixTask.startTime()).getSeconds();
        assertTrue(observedDelay == 1200L || !matrixTask.setupStartTime()
                        .equals(baselineTask.setupStartTime()),
                "同一占用起点下，矩阵任务的实际 startTime 应晚 1200 秒，实际: " + observedDelay);
    }

    private static long setupSeconds(TaskAssignment assignment) {
        return Duration.between(assignment.setupStartTime(), assignment.setupEndTime()).getSeconds();
    }

    private static ScheduleSolution solve(List<SchedulingOrder> orders, SchedulingResource resource,
                                          List<SetupMatrixEntry> matrix) {
        ScheduleProblem problem = new ScheduleProblem(
                "tenant-demo",
                ScheduleProblem.DEFAULT_SANDBOX_ID,
                null,
                null,
                "FORWARD",
                orders,
                List.of(resource),
                List.of(),
                List.of(),
                matrix);
        return new TimefoldSolverAdapter().solve(problem,
                new com.hengce.aps.solver.api.SolveConfig(
                        "timefold", SOLVE_SECONDS, 1, "LATE_ACCEPTANCE",
                        1L, 0L, 0, "FORWARD", true, java.util.Map.of()));
    }

    private static SchedulingOrder order(String orderNo, String materialCode, String family,
                                         UUID resourceId) {
        return new SchedulingOrder(
                UUID.randomUUID(),
                orderNo,
                materialCode,
                family,
                BigDecimal.valueOf(12),
                LocalDate.now(ZoneOffset.UTC).plusDays(3),
                "NORMAL",
                "UNFROZEN",
                UUID.randomUUID(),
                List.of(new SchedulingOperation(
                        10,
                        "OP-10",
                        "工序 10",
                        null,
                        BigDecimal.valueOf(600),
                        BigDecimal.valueOf(600),
                        resourceId,
                        null,
                        List.of(),
                        false)));
    }
}
