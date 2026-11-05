package com.hengce.aps.service.agent;

import com.hengce.aps.service.sandbox.SandboxCompareView;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话式 what-if 结果：必须返回“方案对比”，不是单方案结果。
 *
 * <p>{@code comparison} 直接复用 C32 沙箱 compare 的真实结构；{@code bottleneckChange}
 * 复用该计算器同口径的逐资源利用率。求解未成功时 {@code available=false}，
 * 不伪造对比数据。</p>
 *
 * @param sandboxId 假设运行发生的 WHAT_IF 场景
 * @param baselineSandboxId 对比基线（当前 BASELINE）
 * @param baselineTaskCount 基线场景任务数（实测，不是配置）
 * @param afterTaskCount 对比侧（WHAT_IF 场景）任务数
 * @param baselineEmpty 基线是否为空（任务数为 0）
 * @param comparisonWarnings 基线可比性警告编码；空表示未发现不可比情形
 * @param question 用户问题回显
 * @param appliedChanges 实际生效的求解参数
 * @param solveTaskId 本次假设运行的求解任务主键
 * @param solveStatus 求解状态
 * @param solveDegraded 是否发生引擎降级
 * @param scoreHard 硬约束扣分
 * @param scoreSoft 软约束扣分
 * @param metrics 求解指标
 * @param available 是否存在可用的方案对比
 * @param unavailableReason 无法给出对比时的原因编码
 * @param comparison 方案对比（C32 结构）
 * @param impactedOrders 受影响订单号（来自变更明细）
 * @param impactedOrdersComplete 受影响订单是否完整（变更明细截断时为 false）
 * @param bottleneckChange 瓶颈变化（逐资源利用率）
 * @param answer 中文摘要
 * @param source 证据来源，当前恒为 {@code EVIDENCE}
 */
public record AgentWhatIfView(UUID sandboxId, UUID baselineSandboxId, long baselineTaskCount,
                              long afterTaskCount, boolean baselineEmpty,
                              List<String> comparisonWarnings, String question,
                              List<AppliedChange> appliedChanges, Long solveTaskId, String solveStatus,
                              boolean solveDegraded, Long scoreHard, Long scoreSoft,
                              Map<String, Object> metrics, boolean available, String unavailableReason,
                              SandboxCompareView comparison, List<String> impactedOrders,
                              boolean impactedOrdersComplete, BottleneckChange bottleneckChange,
                              String answer, String source) {

    /**
     * 已应用的求解参数。
     *
     * @param parameter 参数名
     * @param value 参数值
     */
    public record AppliedChange(String parameter, Object value) {
    }

    /**
     * 瓶颈资源变化。
     *
     * @param before 假设前瓶颈资源（利用率最高；无可用时间/资源时为 null）
     * @param after 假设后瓶颈资源
     * @param comparable 两侧是否都可计算瓶颈；false 时 {@code changed} 不代表“未变化”
     * @param notComparableReason 不可比原因编码；可比时为 null
     * @param changed 瓶颈资源是否改变（仅在 {@code comparable=true} 时判定：编码不同或利用率变化 ≥ 0.01）
     * @param metric 口径说明
     * @param resourceDeltas 逐资源利用率差，按绝对变化倒序
     */
    public record BottleneckChange(BottleneckPoint before, BottleneckPoint after, boolean comparable,
                                   String notComparableReason, boolean changed,
                                   String metric, List<ResourceUtilizationDelta> resourceDeltas) {
    }

    /**
     * 瓶颈点。
     *
     * @param resourceCode 资源编码
     * @param utilization 利用率（0–1）
     */
    public record BottleneckPoint(String resourceCode, Double utilization) {
    }

    /**
     * 逐资源利用率差。
     *
     * @param resourceCode 资源编码
     * @param before 假设前利用率
     * @param after 假设后利用率
     * @param delta after - before
     */
    public record ResourceUtilizationDelta(String resourceCode, Double before, Double after, Double delta) {
    }
}
