package com.hengce.aps.solver.dsl;

/**
 * 单条校验问题。
 *
 * @param code 422xx 错误码
 * @param field 出错的字段路径
 * @param message 可读说明
 */
public record DslValidationIssue(String code, String field, String message) {

    /**
     * 序列化为一行文本，便于日志与接口返回。
     *
     * @return 形如 {@code [42203] constraints[3].weight: ...}
     */
    public String format() {
        return "[" + code + "] " + field + ": " + message;
    }
}
