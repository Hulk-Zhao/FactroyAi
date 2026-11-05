package com.hengce.aps.service.strategy;

/**
 * 约束 DSL 版本状态，与 V4 迁移的 status 取值一致。
 */
public enum ConstraintDslStatus {

    /**
     * 草稿：已通过校验但未激活。
     */
    DRAFT,

    /**
     * 激活：同一 code 下唯一。
     */
    ACTIVE,

    /**
     * 已回滚：曾经激活、已被后续版本替换。
     */
    ROLLBACK
}
