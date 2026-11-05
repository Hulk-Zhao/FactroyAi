package com.hengce.aps.solver.api;

/**
 * 求解器统一抽象。业务层只依赖此接口，具体引擎实现位于 solver-timefold / solver-ortools。
 *
 * <p>按 contract-freeze C1 §3.4：同步求解唯一入口为
 * {@link #solve(ScheduleProblem, SolveConfig)}，不再保留 {@code solve(SolveRequest)} 重载。
 * 异步生命周期在 M1 由 aps-service 的求解任务调度负责；本接口仅保留默认占位方法，
 * 未实现时抛出 {@link UnsupportedOperationException}，禁止伪装成功。</p>
 */
public interface SolverAdapter {

    /**
     * 引擎类型。
     *
     * @return 引擎类型
     */
    SolverEngine engine();

    /**
     * 同步求解。
     *
     * @param problem 引擎中立问题
     * @param config 引擎中立配置
     * @return 引擎中立结果；未实现时必须返回 FAILED，不得伪装成功
     */
    ScheduleSolution solve(ScheduleProblem problem, SolveConfig config);

    /**
     * 提交异步求解；M1 未实现。
     *
     * @param problem 引擎中立问题
     * @param config 引擎中立配置
     * @return 异步句柄
     */
    default SolveHandle submitAsync(ScheduleProblem problem, SolveConfig config) {
        throw new UnsupportedOperationException("异步求解尚未实现");
    }

    /**
     * 提前终止异步求解；M1 未实现。
     *
     * @param handle 异步句柄
     */
    default void terminateEarly(SolveHandle handle) {
        throw new UnsupportedOperationException("提前终止尚未实现");
    }

    /**
     * 查询异步进度；M1 未实现。
     *
     * @param handle 异步句柄
     * @return 求解进度
     */
    default SolveProgress getProgress(SolveHandle handle) {
        throw new UnsupportedOperationException("进度查询尚未实现");
    }

    /**
     * 基于既有解做增量重排；M1 未实现。
     *
     * @param problem 引擎中立问题
     * @param config 引擎中立配置
     * @param baseline 基线解
     * @return 引擎中立结果
     */
    default ScheduleSolution solveIncremental(
            ScheduleProblem problem, SolveConfig config, ScheduleSolution baseline) {
        throw new UnsupportedOperationException("增量重排尚未实现");
    }
}
