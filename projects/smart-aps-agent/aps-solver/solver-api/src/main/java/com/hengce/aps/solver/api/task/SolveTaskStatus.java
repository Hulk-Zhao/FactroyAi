package com.hengce.aps.solver.api.task;

import java.util.Locale;

/**
 * 求解任务生命周期状态，与 {@code solve_task.status} 的 CHECK 约束保持一致。
 *
 * <p>M2-SE-03 起 {@code POST /solve-tasks} 为异步提交：先落 {@link #PENDING}，
 * 由调度线程切换为 {@link #RUNNING}，最终进入 {@link #SOLVED}/{@link #FAILED}/
 * {@link #CANCELLED}/{@link #TIMEOUT} 之一的终态。</p>
 */
public enum SolveTaskStatus {

    /**
     * 已受理，等待求解线程。
     */
    PENDING,

    /**
     * 求解中。
     */
    RUNNING,

    /**
     * 求解成功且结果已落库。
     */
    SOLVED,

    /**
     * 求解失败（含引擎异常降级）。
     */
    FAILED,

    /**
     * 用户取消或服务关闭终止。
     */
    CANCELLED,

    /**
     * 求解超时（含引擎超时降级）。
     */
    TIMEOUT;

    /**
     * 是否终态。
     *
     * @return {@code true} 表示 PENDING/RUNNING 之外的状态
     */
    public boolean isTerminal() {
        return this != PENDING && this != RUNNING;
    }

    /**
     * 解析数据库中的状态码，空值按 {@link #PENDING} 处理。
     *
     * @param code 状态码，忽略大小写
     * @return 状态枚举
     * @throws IllegalArgumentException 状态码不合法
     */
    public static SolveTaskStatus fromNullable(String code) {
        if (code == null || code.isBlank()) {
            return PENDING;
        }
        return valueOf(code.trim().toUpperCase(Locale.ROOT));
    }
}
