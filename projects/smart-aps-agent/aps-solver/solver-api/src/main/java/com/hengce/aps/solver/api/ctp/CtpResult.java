package com.hengce.aps.solver.api.ctp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * C53 CTP 询单响应。
 *
 * <p>只读：不写 {@code plan_version}/{@code task}/{@code orders}/{@code solve_task}，
 * {@code mainPlanWritten} 恒为 false。{@code material} 为开放对象，稳定键见 C53 契约；
 * 物料明细不依赖 C54。</p>
 *
 * @param inquiryId 询单标识
 * @param verdict 询单结论
 * @param promisedDate 承诺日期；仅 PROMISED 非空，必须来自求解
 * @param promisedQty 承诺数量；仅 PROMISED 非空
 * @param materialReadyAt 物料可齐日；未知为 null
 * @param capacityVerifiedAt 产能验证完成时间；未验证为 null
 * @param horizonEnd 视野结束；仅 OUT_OF_HORIZON 非空
 * @param solver ACCURATE 验证摘要；未验证为 null
 * @param material 物料摘要开放对象
 * @param evidence 证据条目，最多 8 条
 * @param assumptions 计算假设
 * @param message 确定性说明
 * @param generatedAt 响应生成时间
 * @param mainPlanWritten 恒为 false
 */
public record CtpResult(
        UUID inquiryId,
        CtpVerdict verdict,
        LocalDate promisedDate,
        BigDecimal promisedQty,
        LocalDate materialReadyAt,
        OffsetDateTime capacityVerifiedAt,
        OffsetDateTime horizonEnd,
        CtpSolverInfo solver,
        Map<String, Object> material,
        List<Map<String, Object>> evidence,
        List<String> assumptions,
        String message,
        OffsetDateTime generatedAt,
        boolean mainPlanWritten
) {

    public CtpResult {
        material = material == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(material));
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
    }

    /**
     * 求解过程摘要。
     *
     * @param solveTaskId 恒为 null：CTP 不创建 solve_task
     */
    public record CtpSolverInfo(
            String engine,
            Long seed,
            BigDecimal hardScore,
            BigDecimal softScore,
            Long durationMs,
            String terminationReason,
            Long solveTaskId
    ) {
    }
}
