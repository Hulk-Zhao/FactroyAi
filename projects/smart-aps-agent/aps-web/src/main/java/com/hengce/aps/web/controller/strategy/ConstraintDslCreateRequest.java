package com.hengce.aps.web.controller.strategy;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建约束 DSL 版本请求。
 *
 * @param code DSL 标识
 * @param yamlText DSL 文本
 */
public record ConstraintDslCreateRequest(@NotBlank String code, @NotBlank String yamlText) {
}
