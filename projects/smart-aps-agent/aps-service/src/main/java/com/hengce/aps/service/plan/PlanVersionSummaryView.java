package com.hengce.aps.service.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 计划版本列表项（C24 列表端点）。
 *
 * <p>只含版本元信息，不含任务明细；任务明细仍走 {@code GET /plans/{id}}。</p>
 *
 * @param planId 计划版本主键（对外 planId）
 * @param planNo 计划业务号
 * @param version 版本号
 * @param status 状态
 * @param publishedAt 发布时间，未发布版本为空
 * @param publishedBy 发布人，可为空
 * @param approver 审批人，可为空
 * @param taskCount 快照任务数
 * @param rollbackOf 回滚来源版本主键，非回滚版本为空
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PlanVersionSummaryView(
        UUID planId,
        String planNo,
        int version,
        String status,
        OffsetDateTime publishedAt,
        UUID publishedBy,
        String approver,
        int taskCount,
        UUID rollbackOf) {
}
