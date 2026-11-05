package com.hengce.aps.service.plan;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 计划动作响应，对齐 C24 的 PlanAction。
 *
 * @param planId 计划版本主键（对外 planId）
 * @param planNo 计划业务号 {@code PLAN-YYYYMMDD-NNN}
 * @param version 版本号（C24 version_no，从 1 递增）
 * @param targetVersionNo 回滚目标版本号；发布时为空
 * @param status 状态
 * @param publishedAt 发布时间
 * @param affectedTaskCount 本次受影响任务数（发布为回填数，回滚为重指向数）
 * @param sandboxId 场景标识
 * @param taskCount 该版本快照任务数
 * @param approver 审批人
 * @param reason 原因
 * @param rollbackOf 被回滚的源版本主键
 */
public record PlanActionView(
        UUID planId,
        String planNo,
        int version,
        Integer targetVersionNo,
        String status,
        OffsetDateTime publishedAt,
        int affectedTaskCount,
        UUID sandboxId,
        int taskCount,
        String approver,
        String reason,
        UUID rollbackOf) {
}
