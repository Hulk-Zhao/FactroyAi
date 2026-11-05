package com.hengce.aps.solver.api;

import com.hengce.aps.common.constant.ApsConstants;

/**
 * 求解引擎类型。
 */
public enum SolverEngine {

    /**
     * Timefold Solver，默认引擎。
     */
    TIMEFOLD("timefold"),

    /**
     * OR-Tools CP-SAT，MVP 预留。
     */
    ORTOOLS("ortools");

    private final String code;

    SolverEngine(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 根据引擎编码解析枚举，空值回退到默认引擎。
     *
     * @param code 引擎编码
     * @return 引擎类型
     */
    public static SolverEngine fromCode(String code) {
        if (code == null || code.isBlank()) {
            return fromCode(ApsConstants.DEFAULT_SOLVER_ENGINE);
        }
        for (SolverEngine engine : values()) {
            if (engine.code.equalsIgnoreCase(code)) {
                return engine;
            }
        }
        throw new IllegalArgumentException("未知的求解引擎编码: " + code);
    }
}
