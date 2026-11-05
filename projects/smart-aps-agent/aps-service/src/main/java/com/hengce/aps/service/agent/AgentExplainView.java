package com.hengce.aps.service.agent;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 归因解释结果（GET /agent/explain/{taskId}）。
 *
 * <p>当前只有 {@code source=EVIDENCE} 形态：所有字段都来自
 * {@code constraint_justification} 与对应 {@code solve_task}。{@code source=LLM} 的
 * 位置通过 {@link LlmStatus} 预留，V05-01 接入模型后由框架填充，本端点不实现模型客户端。</p>
 *
 * @param taskId 请求路径中的排产任务主键（{@code task.id}）；归因按 {@code solve_task} 持久化，
 *     通过 {@code implicated_facts} 反查，映射不唯一时返回 {@code available=false}
 * @param taskType 固定为 {@code TASK}（路径参数是排产任务）
 * @param available 是否存在可用的确定性解释证据
 * @param source 证据来源：{@code LLM}（模型生成 narrative）或 {@code EVIDENCE}（降级为确定性证据）
 * @param noDataReason 无解释数据时的原因编码，可空
 * @param sourceSolveTaskId 证据所属求解任务主键（仅在映射唯一时有值）
 * @param sourceSolveTaskCandidates 歧义时出现的候选求解任务主键，绝不猜测
 * @param solveTaskStatus 求解任务状态
 * @param scoreHard 硬约束扣分
 * @param scoreSoft 软约束扣分
 * @param unassignedTaskCount 未排产任务数（来自 metrics，缺失为 null）
 * @param degraded 是否发生引擎降级
 * @param metrics 求解任务指标原文（来自 solve_task.metrics）
 * @param justificationIds 证据归因主键列表
 * @param constraintImpacts 逐约束证据
 * @param planVersions 受影响任务所属计划版本
 * @param planVersionEvidenceTruncated 计划版本反查是否因任务数过多被截断
 * @param summary 确定性摘要
 * @param narrative 面向用户的解读；模型可用时为 LLM 生成，否则为产品定稿降级文案
 * @param causes 契约解释原因列表
 * @param suggestions 建议列表（模型未返回时为空）
 * @param llm 模型生成能力的降级状态
 */
public record AgentExplainView(Long taskId, String taskType, boolean available, String source,
                               String noDataReason, Long sourceSolveTaskId,
                               List<Long> sourceSolveTaskCandidates, String solveTaskStatus,
                               Long scoreHard, Long scoreSoft, Long unassignedTaskCount,
                               boolean degraded, Map<String, Object> metrics,
                               List<String> justificationIds, List<ConstraintImpact> constraintImpacts,
                               List<PlanVersionEvidence> planVersions, boolean planVersionEvidenceTruncated,
                               String summary, String narrative, List<ExplainCause> causes,
                               List<String> suggestions, LlmStatus llm) {

    /**
     * 单条约束对评分的贡献，全部字段来自 {@code constraint_justification}。
     *
     * @param justificationId 归因主键
     * @param constraintId 约束编码
     * @param constraintName 约束名称
     * @param scoreImpact HARD/SOFT
     * @param hard 是否硬约束
     * @param weight 软约束权重
     * @param deduction 软约束扣分
     * @param affectedTaskIds 受影响任务主键
     * @param description 归因说明（DB 原文，缺失时由真实字段拼装）
     */
    public record ConstraintImpact(UUID justificationId, String constraintId, String constraintName,
                                   String scoreImpact, boolean hard, Integer weight, BigDecimal deduction,
                                   List<Long> affectedTaskIds, String description) {
    }

    /**
     * 计划版本证据。
     *
     * @param planId 计划版本主键
     * @param planNo 计划号
     * @param versionNo 版本号
     * @param status 版本状态
     * @param publishedAt 发布时间
     * @param taskCount 版本任务数
     */
    public record PlanVersionEvidence(UUID planId, String planNo, Integer versionNo, String status,
                                      OffsetDateTime publishedAt, Integer taskCount) {
    }

    /**
     * 契约解释原因。
     *
     * @param type 原因类型（约束编码）
     * @param description 说明
     * @param evidence 证据字段
     */
    public record ExplainCause(String type, String description, Map<String, Object> evidence) {
    }

    /**
     * 模型生成状态与证据来源。
     *
     * @param configured 模型服务是否已配置且配置完整
     * @param provider 实际 provider 名；未配置为 null
     * @param fallbackReason 降级原因编码（{@code NO_PROVIDER_CONFIGURED} / {@code TIMEOUT} /
     *     {@code PROVIDER_ERROR} / {@code PROVIDER_UNAVAILABLE} / {@code INVALID_OUTPUT} 等）；
     *     成功时为 null
     * @param fallbackSource 降级后 narrative 的来源，固定 {@code EVIDENCE}；成功时为 null
     * @param latencyMillis 模型调用耗时（毫秒）；未调用为 null
     * @param promptTokens 输入 token 数；未知为 null
     * @param completionTokens 输出 token 数；未知为 null
     */
    public record LlmStatus(boolean configured, String provider, String fallbackReason,
                            String fallbackSource, Long latencyMillis, Integer promptTokens,
                            Integer completionTokens) {

        /**
         * 兼容构造：不带 token/耗时。
         *
         * @param configured 是否配置完整
         * @param provider provider 名
         * @param fallbackReason 降级原因
         * @param fallbackSource 降级来源
         */
        public LlmStatus(boolean configured, String provider, String fallbackReason, String fallbackSource) {
            this(configured, provider, fallbackReason, fallbackSource, null, null, null);
        }
    }
}
