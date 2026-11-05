package com.hengce.aps.admin.config;

import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.ortools.OrtoolsSolverAdapter;
import com.hengce.aps.solver.timefold.TimefoldSolverAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 求解器装配：组合根在此选择启用的引擎实现，业务层只看到 SolverAdapter。
 */
@Configuration
public class SolverConfiguration {

    /**
     * Timefold 引擎适配器；求解槽位并发度与等待上限来自 {@code aps.solve.*}。
     *
     * @param maxConcurrentSolvers 并发求解槽位；0 = 按 CPU 自动解析（至少 2）
     * @param slotWaitSeconds 等待槽位上限（秒）
     * @return 适配器
     */
    @Bean
    public SolverAdapter timefoldSolverAdapter(
            @Value("${aps.solve.max-concurrent-solvers:0}") int maxConcurrentSolvers,
            @Value("${aps.solve.solve-slot-wait-seconds:5}") int slotWaitSeconds) {
        return new TimefoldSolverAdapter(maxConcurrentSolvers, slotWaitSeconds);
    }

    /**
     * OR-Tools 引擎适配器（预留）。
     *
     * @return 适配器
     */
    @Bean
    public SolverAdapter ortoolsSolverAdapter() {
        return new OrtoolsSolverAdapter();
    }
}
