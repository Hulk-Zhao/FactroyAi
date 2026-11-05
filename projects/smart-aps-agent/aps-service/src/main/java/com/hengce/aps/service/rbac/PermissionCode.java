package com.hengce.aps.service.rbac;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限码闭集（C30 / 25.1.1）：严格复用 V1 {@code permission.code} 的 18 个值，不得新增/改名。
 */
public enum PermissionCode {

    /** 基础数据查看。 */
    MD_READ,
    /** 基础数据维护。 */
    MD_WRITE,
    /** 工艺查看。 */
    PR_READ,
    /** 工艺维护。 */
    PR_WRITE,
    /** 订单查看。 */
    OM_READ,
    /** 订单维护。 */
    OM_WRITE,
    /** 交期预估。 */
    OM_ATP,
    /** 订单冻结。 */
    ORDER_FREEZE,
    /** 策略查看。 */
    SS_READ,
    /** 策略维护。 */
    SS_WRITE,
    /** 触发求解。 */
    SE_SOLVE,
    /** 计划调整。 */
    SCHEDULE_ADJUST,
    /** 计划发布。 */
    SCHEDULE_PUBLISH,
    /** 用户管理。 */
    SYS_USER,
    /** 角色权限管理。 */
    SYS_ROLE,
    /** 审计查看。 */
    SYS_AUDIT,
    /** Agent 异常事件查看。 */
    AGENT_EVENT,
    /** Agent 决策确认。 */
    AGENT_CONFIRM;

    /**
     * 全部合法权限码。
     */
    private static final Set<String> CODES = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    /**
     * 判断权限码是否在闭集内。
     *
     * @param code 权限码
     * @return true 表示合法
     */
    public static boolean isValid(String code) {
        return code != null && CODES.contains(code);
    }
}
