package com.hengce.aps.service.schedule.atp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * ATP 交期预估响应。
 *
 * <p>契约要点（C46）：</p>
 * <ul>
 *   <li>{@code accuracy} 必填，FAST 一律 {@code ESTIMATE}；</li>
 *   <li>{@code status=TIMEOUT} 时 {@code earliestDeliveryDate/earliestDate/leadTimeDays} 必须为空，
 *       不允许伪造精确日期；</li>
 *   <li>{@code capacityLockSuggestion} 仅是可执行建议，{@code advisoryOnly=true}，
 *       ATP 不写 task/plan_version/orders；</li>
 *   <li>{@code earliestDate} 为 C41 兼容别名，与 {@code earliestDeliveryDate} 同值。</li>
 * </ul>
 *
 * @param materialCode           物料编码
 * @param qty                    数量
 * @param requestedDate          请求交期
 * @param orderNo                订单号（回显；无既有订单时为 ATP- 前缀的临时号）
 * @param mode                   档位
 * @param accuracy               准确性口径
 * @param status                 处理状态
 * @param feasible               是否满足请求交期；无确定结论（TIMEOUT/不可行）时为 null
 * @param earliestDeliveryDate   最早可交付日期
 * @param earliestDate           C41 兼容别名
 * @param leadTimeDays           距今天数（自然日）；无日期时为 null
 * @param bottleneckResourceCode 首要瓶颈资源编码（C41 兼容）
 * @param bottlenecks            瓶颈明细
 * @param suggestions            可执行建议
 * @param capacityLockSuggestion 产能锁定建议（仅建议）
 * @param solve                  求解/估算过程信息
 * @param assumptions            计算假设
 * @param mainPlanWritten        是否写主计划；ATP 恒为 false
 * @param message                结论说明
 */
public record AtpResult(
        String materialCode,
        BigDecimal qty,
        LocalDate requestedDate,
        String orderNo,
        AtpMode mode,
        AtpAccuracy accuracy,
        AtpStatus status,
        Boolean feasible,
        LocalDate earliestDeliveryDate,
        LocalDate earliestDate,
        Integer leadTimeDays,
        String bottleneckResourceCode,
        List<AtpBottleneck> bottlenecks,
        List<AtpSuggestion> suggestions,
        CapacityLockSuggestion capacityLockSuggestion,
        AtpSolveInfo solve,
        List<String> assumptions,
        boolean mainPlanWritten,
        String message) {
}
