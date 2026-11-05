package com.hengce.aps.solver.dsl;

/**
 * DSL 校验失败异常，携带完整的 422xx 校验报告。
 */
public class DslValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient DslValidationReport report;

    /**
     * 构造异常。
     *
     * @param report 校验报告
     */
    public DslValidationException(DslValidationReport report) {
        super(summarize(report));
        this.report = report;
    }

    /**
     * 校验报告。
     *
     * @return 报告
     */
    public DslValidationReport getReport() {
        return report;
    }

    /**
     * 主错误码。
     *
     * @return 422xx 错误码
     */
    public String getErrorCode() {
        return report.primaryCode();
    }

    private static String summarize(DslValidationReport report) {
        StringBuilder builder = new StringBuilder("DSL 校验失败");
        for (DslValidationIssue issue : report.issues()) {
            builder.append("; ").append(issue.format());
        }
        return builder.toString();
    }
}
