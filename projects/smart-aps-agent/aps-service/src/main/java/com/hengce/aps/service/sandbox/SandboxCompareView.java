package com.hengce.aps.service.sandbox;

import java.util.List;
import java.util.UUID;

/**
 * 场景对比结果（C32 compare），方向固定为 {@code id - targetId}。
 *
 * @param sandboxId 被比较方
 * @param targetId 基线方
 * @param dueDateDeltaMinutes 交期超期分钟差
 * @param utilizationDelta 资源利用率均值差（0–1，保留 3 位）
 * @param setupCountDelta 换型次数差
 * @param addedTaskCount 仅被比较方存在的业务键数
 * @param removedTaskCount 仅基线方存在的业务键数
 * @param movedTaskCount 两侧都存在但时间/资源变化的业务键数
 * @param changedTasks 变更明细，最多 200 条
 * @param truncated 明细是否被截断；计数字段始终为全量
 */
public record SandboxCompareView(
        UUID sandboxId,
        UUID targetId,
        long dueDateDeltaMinutes,
        double utilizationDelta,
        int setupCountDelta,
        int addedTaskCount,
        int removedTaskCount,
        int movedTaskCount,
        List<ChangedTaskView> changedTasks,
        boolean truncated) {

    /**
     * 单条任务变更。
     *
     * @param taskId 任务主键（ADDED/MOVED 为被比较方，REMOVED 为基线方）
     * @param orderNo 订单号
     * @param resourceCode 资源编码
     * @param changeType ADDED / REMOVED / MOVED
     */
    public record ChangedTaskView(long taskId, String orderNo, String resourceCode, String changeType) {
    }
}
