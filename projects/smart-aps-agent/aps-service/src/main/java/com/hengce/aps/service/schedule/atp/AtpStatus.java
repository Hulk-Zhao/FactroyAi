package com.hengce.aps.service.schedule.atp;

/**
 * ATP 请求的处理状态。
 *
 * <p>{@code OK} 表示给出了交期（可能是 ESTIMATE 或 VERIFIED）；
 * {@code INFEASIBLE} 表示在可用资源/时间窗内无解；
 * {@code TIMEOUT} 表示限时求解未拿到完整可行解，按契约不得伪造日期；
 * {@code FAILED} 表示求解引擎返回失败。</p>
 */
public enum AtpStatus {

    /**
     * 已给出交期。
     */
    OK,

    /**
     * 无可行排程或没有可用资源。
     */
    INFEASIBLE,

    /**
     * 限时求解超时且结果不完整。
     */
    TIMEOUT,

    /**
     * 求解引擎失败。
     */
    FAILED
}
