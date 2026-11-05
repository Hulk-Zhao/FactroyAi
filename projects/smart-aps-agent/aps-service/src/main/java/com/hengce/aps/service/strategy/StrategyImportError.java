package com.hengce.aps.service.strategy;

/**
 * 策略导入校验错误项（C19）：{@code {field, code, message}}。
 *
 * <p><b>职责划分（勿混用）</b>：</p>
 * <ul>
 *   <li><b>顶层 {@code code}</b>（{@code Result.code}）：HTTP/端点层错误码，表示整次请求的结果类别，
 *   如 {@code 42200}（不可处理）、{@code 40901}（冲突）。</li>
 *   <li><b>{@code errors[].code}</b>：与契约 OpenAPI 声明一致，类型为 <b>string</b>，取值恒为
 *   {@code "42200"}——它是<b>HTTP 层码的字符串投影</b>，<b>不是字段级规则码</b>。
 *   字段级的差异（必填缺失、方向非法、规则条数/权重/未知编码等）一律放在 {@code field}
 *   与 {@code message} 里，例如 {@code rules} 的 message 可能带 {@code [42203]} 这类细分文案，
 *   但 {@code errors[].code} 不承载这些细分编码，避免后人误以为这里可以放规则码。</li>
 * </ul>
 *
 * @param field 出错字段路径，如 {@code strategy.name}、{@code rules[0].weight}
 * @param code 端点层错误码字符串（恒为 {@code "42200"}，非字段级规则码）
 * @param message 可读错误信息
 */
public record StrategyImportError(
        String field,
        String code,
        String message) {
}
