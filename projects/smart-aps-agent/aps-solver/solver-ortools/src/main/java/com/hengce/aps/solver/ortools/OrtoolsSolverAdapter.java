package com.hengce.aps.solver.ortools;

import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OR-Tools CP-SAT 适配器骨架，MVP 阶段仅保留占位实现。
 *
 * <p>按 contract-freeze C1 已切换为中立签名；求解逻辑为 v0.2 预留能力，
 * 当前返回明确的 {@code FAILED} 结果，不伪装成功。</p>
 */
public class OrtoolsSolverAdapter implements SolverAdapter {

    /**
     * 引擎编码（外部小写）。
     */
    private static final String ENGINE_CODE = "ortools";

    /**
     * 未实现提示。
     */
    private static final String NOT_IMPLEMENTED_MESSAGE = "OR-Tools 求解逻辑为 v0.2 预留能力";

    @Override
    public SolverEngine engine() {
        return SolverEngine.ORTOOLS;
    }

    @Override
    public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
        Objects.requireNonNull(problem, "problem 不能为空");
        Objects.requireNonNull(config, "config 不能为空");
        return new ScheduleSolution(
                "FAILED",
                null,
                List.of(),
                List.of(),
                new SolveMetrics(
                        0L,
                        "TERMINATED",
                        List.of(),
                        0,
                        Map.of("engine", ENGINE_CODE, "implemented", false)),
                NOT_IMPLEMENTED_MESSAGE);
    }
}
