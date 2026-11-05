package com.hengce.aps.solver.dsl;

/**
 * 约束 DSL 加载器，加载即校验；校验失败抛出 {@link DslValidationException}。
 */
public interface ConstraintDslLoader {

    /**
     * 加载并校验 DSL。
     *
     * @param source YAML 文本
     * @return 规范化文档
     * @throws DslValidationException 校验失败，异常携带 422xx 校验报告
     */
    ConstraintDslDocument load(String source);
}
