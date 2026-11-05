package com.hengce.aps.solver.dsl;

import java.util.List;

/**
 * DSL 校验报告。
 *
 * @param valid 是否通过校验
 * @param issues 问题列表，按发现顺序
 */
public record DslValidationReport(boolean valid, List<DslValidationIssue> issues) {

    /**
     * 规范化构造。
     *
     * @param valid 是否通过
     * @param issues 问题列表
     */
    public DslValidationReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /**
     * 通过的报告。
     *
     * @return 空问题报告
     */
    public static DslValidationReport ok() {
        return new DslValidationReport(true, List.of());
    }

    /**
     * 失败的报告。
     *
     * @param issues 问题列表
     * @return 报告
     */
    public static DslValidationReport failed(List<DslValidationIssue> issues) {
        return new DslValidationReport(false, issues);
    }

    /**
     * 主错误码，取第一条问题的编码。
     *
     * @return 错误码；通过校验时为空串
     */
    public String primaryCode() {
        return issues.isEmpty() ? "" : issues.get(0).code();
    }
}
