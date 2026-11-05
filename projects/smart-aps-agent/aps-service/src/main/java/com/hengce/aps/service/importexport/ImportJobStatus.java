package com.hengce.aps.service.importexport;

/**
 * 异步导入任务状态机（C39）：{@code PENDING -> RUNNING -> SUCCEEDED | FAILED | CANCELLED}。
 */
public enum ImportJobStatus {

    /** 已受理，排队等待执行。 */
    PENDING,

    /** 正在执行。 */
    RUNNING,

    /** 执行成功并已提交。 */
    SUCCEEDED,

    /** 执行失败，整批回滚。 */
    FAILED,

    /** 用户取消（仅 PENDING/RUNNING 可取消）。 */
    CANCELLED
}
