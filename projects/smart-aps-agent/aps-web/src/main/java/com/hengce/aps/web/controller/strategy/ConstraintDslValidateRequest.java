package com.hengce.aps.web.controller.strategy;

import jakarta.validation.constraints.NotBlank;

/**
 * 约束 DSL 校验请求。
 *
 * @param yamlText DSL 文本
 */
public record ConstraintDslValidateRequest(@NotBlank String yamlText) {
}
