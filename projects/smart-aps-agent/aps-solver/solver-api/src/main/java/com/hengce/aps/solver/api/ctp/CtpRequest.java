package com.hengce.aps.solver.api.ctp;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * C53 CTP 询单入参（同步、只读）。
 *
 * <p>{@code materialPolicy} 与 {@code allowSubstitute} 的合并规则：任一为替代即生效
 * {@code SUBSTITUTE}，否则 {@code STRICT}。V1 不拆批，{@code allowSplitBatch=true}
 * 不改变承诺数量。</p>
 *
 * @param materialCode 物料编码
 * @param qty 数量，0 < qty <= 1e9
 * @param requestedDate 客户请求交期
 * @param customerCode 客户编码；仅审计/展示，不阻断询单
 * @param priority 优先级
 * @param allowSplitBatch 是否允许拆批；缺省 false
 * @param allowSubstitute 是否允许替代料；缺省 false
 * @param materialPolicy 物料策略；缺省 STRICT
 * @param timeoutSeconds 限时秒数，1..15，缺省 15
 */
public record CtpRequest(
        String materialCode,
        BigDecimal qty,
        LocalDate requestedDate,
        String customerCode,
        CtpPriority priority,
        Boolean allowSplitBatch,
        Boolean allowSubstitute,
        CtpMaterialPolicy materialPolicy,
        Integer timeoutSeconds
) {
}
