package com.hengce.aps.solver.api.duration;

import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingResource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 工序加工时长（runSeconds）的唯一计算口径，按 contract-freeze C2。
 *
 * <p>三种产能模式都依赖资源参数，任何资源差异都会改变时长：</p>
 * <ul>
 *   <li>UNIT_PER_HOUR：{@code ceil(qty * 3600 / (capacity_value * efficiency))}</li>
 *   <li>SECONDS_PER_UNIT：{@code ceil(qty * standard_seconds / efficiency)}</li>
 *   <li>BATCH_SECONDS：{@code ceil(ceil(qty / batch_capacity) * capacity_value / efficiency)}</li>
 * </ul>
 *
 * <p>求解模型建模型时与装配阶段前置校验（C15 §20.3.1.4）必须共用本类，
 * 避免两处公式分叉。</p>
 */
public final class OperationRunDuration {

    /**
     * 每小时秒数。
     */
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    /**
     * 默认效率系数。
     */
    private static final BigDecimal DEFAULT_EFFICIENCY = BigDecimal.ONE;

    private OperationRunDuration() {
    }

    /**
     * 计算加工秒数。
     *
     * @param resource 资源快照
     * @param operation 工序快照
     * @param qty 订单数量，可空按 0
     * @return 加工秒数
     */
    public static long seconds(SchedulingResource resource, SchedulingOperation operation, BigDecimal qty) {
        Objects.requireNonNull(resource, "resource 不能为空");
        Objects.requireNonNull(operation, "operation 不能为空");
        BigDecimal quantity = qty == null ? BigDecimal.ZERO : qty;
        BigDecimal efficiency = resource.efficiency() == null ? DEFAULT_EFFICIENCY : resource.efficiency();
        if (efficiency.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("资源效率必须大于 0: " + resource.code());
        }
        return switch (capacityMode(resource)) {
            case "UNIT_PER_HOUR" -> unitPerHourSeconds(resource, quantity, efficiency);
            case "SECONDS_PER_UNIT" -> secondsPerUnitSeconds(operation, quantity, efficiency);
            case "BATCH_SECONDS" -> batchSeconds(resource, quantity, efficiency);
            default -> throw new IllegalArgumentException("未知的产能模式: " + resource.capacityMode());
        };
    }

    private static long unitPerHourSeconds(
            SchedulingResource resource, BigDecimal quantity, BigDecimal efficiency) {
        BigDecimal capacity = requirePositive(resource.capacityValue(), "capacity_value", resource.code());
        BigDecimal hourlyCapacity = capacity.multiply(efficiency);
        return quantity.multiply(SECONDS_PER_HOUR).divide(hourlyCapacity, 0, RoundingMode.CEILING)
                .longValueExact();
    }

    private static long secondsPerUnitSeconds(
            SchedulingOperation operation, BigDecimal quantity, BigDecimal efficiency) {
        BigDecimal standardSeconds = requirePositive(
                operation.standardSeconds(), "standard_seconds", operation.operationCode());
        return quantity.multiply(standardSeconds).divide(efficiency, 0, RoundingMode.CEILING)
                .longValueExact();
    }

    private static long batchSeconds(
            SchedulingResource resource, BigDecimal quantity, BigDecimal efficiency) {
        BigDecimal capacity = requirePositive(resource.capacityValue(), "capacity_value", resource.code());
        BigDecimal batchCapacity = resource.batchCapacity() == null
                ? BigDecimal.ONE
                : BigDecimal.valueOf(resource.batchCapacity());
        if (batchCapacity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("batch_capacity 必须大于 0: " + resource.code());
        }
        BigDecimal batches = quantity.divide(batchCapacity, 0, RoundingMode.CEILING);
        return batches.multiply(capacity).divide(efficiency, 0, RoundingMode.CEILING)
                .longValueExact();
    }

    private static String capacityMode(SchedulingResource resource) {
        if (resource.capacityMode() == null) {
            throw new IllegalArgumentException("资源缺少 capacity_mode: " + resource.code());
        }
        return resource.capacityMode();
    }

    private static BigDecimal requirePositive(BigDecimal value, String field, String owner) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " 必须大于 0: " + owner);
        }
        return value;
    }
}
