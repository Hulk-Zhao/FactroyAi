package com.hengce.aps.service.strategy;

/**
 * 创建（升版）约束 DSL 命令。
 *
 * @param code DSL 标识，同一标识下多版本
 * @param yamlText YAML 文本；单数 {@code constraint:} 别名会被规范化为数组
 */
public record ConstraintDslCreateCommand(String code, String yamlText) {
}
