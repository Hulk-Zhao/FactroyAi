package com.hengce.aps.service.rbac;

import java.util.Set;

/**
 * 角色码常量（C30）：V1 种子 5 角色，不可删除、不可改码。
 */
public final class RoleCodes {

    /** 系统管理员。 */
    public static final String ADMIN = "ADMIN";

    /** 计划员。 */
    public static final String PLANNER = "PLANNER";

    /** 工艺工程师。 */
    public static final String PROCESS_ENGINEER = "PROCESS_ENGINEER";

    /** 销售。 */
    public static final String SALES = "SALES";

    /** 只读用户。 */
    public static final String VIEWER = "VIEWER";

    /**
     * 内置角色码集合。
     */
    public static final Set<String> BUILTIN = Set.of(ADMIN, PLANNER, PROCESS_ENGINEER, SALES, VIEWER);

    private RoleCodes() {
    }
}
