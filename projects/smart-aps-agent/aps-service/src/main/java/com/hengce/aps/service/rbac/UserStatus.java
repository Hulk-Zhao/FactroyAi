package com.hengce.aps.service.rbac;

/**
 * 用户状态闭集（V1 {@code users.status}）。
 */
public enum UserStatus {

    /** 正常。 */
    ACTIVE,
    /** 已停用。 */
    DISABLED,
    /** 锁定（登录失败 5 次，15 分钟内拒绝）。 */
    LOCKED
}
