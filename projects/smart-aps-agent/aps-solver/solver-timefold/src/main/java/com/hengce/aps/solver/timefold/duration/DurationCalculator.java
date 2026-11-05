package com.hengce.aps.solver.timefold.duration;

import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.duration.OperationRunDuration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 按 contract-freeze C2 计算工序时长，供计划模型构建使用。
 *
 * <p>三种产能模式（UNIT_PER_HOUR / SECONDS_PER_UNIT / BATCH_SECONDS）都依赖资源参数，
 * 具体公式由 {@link OperationRunDuration} 统一实现，本类只负责拆分
 * {@code setup_seconds}（独立成段并向上取整）。</p>
 */
public final class DurationCalculator {

    private DurationCalculator() {
    }

    /**
     * 计算工序时长。
     *
     * @param resource 资源快照
     * @param operation 工序快照
     * @param qty 订单数量，可空按 0
     * @return 时长分解
     */
    public static TaskDuration calculate(
            SchedulingResource resource, SchedulingOperation operation, BigDecimal qty) {
        Objects.requireNonNull(resource, "resource 不能为空");
        Objects.requireNonNull(operation, "operation 不能为空");
        long setupSeconds = ceilingSeconds(operation.setupSeconds());
        long runSeconds = OperationRunDuration.seconds(resource, operation, qty);
        return new TaskDuration(setupSeconds, runSeconds);
    }

    private static long ceilingSeconds(BigDecimal seconds) {
        if (seconds == null) {
            return 0L;
        }
        if (seconds.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("setup_seconds 不能为负数");
        }
        return seconds.setScale(0, RoundingMode.CEILING).longValueExact();
    }
}
