package com.hengce.aps.service.audit;

/**
 * 审计事件类型闭集（C31 / 25.2.1），共 17 个。
 *
 * <p>前 3 个为 M2 既有动作，名称不得修改；新增动作只对 M3 新行为启用，不追溯补写历史动作。
 * {@code AGENT_EVENT_REPORTED} 为 C45 新增，复用 {@code audit_log} 承载 Agent 事件，
 * 资源类型闭集由 C45 扩展为 {@code USER/ROLE/ORDER/PLAN/SANDBOX/TASK/IMPORT/AGENT_EVENT}。</p>
 */
public enum AuditEventType {

    /** 订单冻结（既有）。 */
    ORDER_FROZEN("ORDER"),
    /** 订单优先级变更（既有）。 */
    ORDER_PRIORITY_CHANGED("ORDER"),
    /** 订单释放（既有）。 */
    ORDER_RELEASED("ORDER"),
    /** 登录成功。 */
    AUTH_LOGIN("USER"),
    /** 登录失败（独立事务）。 */
    AUTH_LOGIN_FAILED("USER"),
    /** 创建用户。 */
    USER_CREATED("USER"),
    /** 修改用户 / 角色分配。 */
    USER_UPDATED("USER"),
    /** 创建角色。 */
    ROLE_CREATED("ROLE"),
    /** 角色权限替换。 */
    ROLE_PERMISSION_CHANGED("ROLE"),
    /** 计划发布。 */
    PLAN_PUBLISHED("PLAN"),
    /** 计划回滚。 */
    PLAN_ROLLBACK("PLAN"),
    /** 计划下发。 */
    PLAN_DISPATCHED("PLAN"),
    /** 创建 WHAT_IF 场景。 */
    SANDBOX_CREATED("SANDBOX"),
    /** 场景 apply 成功。 */
    SANDBOX_APPLIED("SANDBOX"),
    /** 导入执行落库成功。 */
    IMPORT_EXECUTED("IMPORT"),
    /** 任务调整（含 SPLIT/MERGE）。 */
    TASK_ADJUSTED("TASK"),
    /** Agent 事件上报（C45 新增；resource_id 为幂等键）。 */
    AGENT_EVENT_REPORTED("AGENT_EVENT");

    private final String resourceType;

    AuditEventType(String resourceType) {
        this.resourceType = resourceType;
    }

    /**
     * 资源类型闭集取值 USER/ROLE/ORDER/PLAN/SANDBOX/TASK/IMPORT。
     *
     * @return 资源类型
     */
    public String getResourceType() {
        return resourceType;
    }
}
