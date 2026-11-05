package com.hengce.aps.service.strategy;

import java.util.Map;

/**
 * 策略导入请求（C19 冻结形状）：{@code {payload, dryRun}}。
 *
 * <p>{@code payload} 即导出响应中的 {@code data}（{@code {strategy, rules, checksum}}）；
 * {@code dryRun} 缺省 {@code false}，为 {@code true} 时只校验不落库。</p>
 *
 * @param payload 导出载荷 {@code {strategy, rules, checksum}}
 * @param dryRun 是否只校验不落库；{@code null} 视为 {@code false}
 */
public record StrategyImportRequest(
        Map<String, Object> payload,
        Boolean dryRun) {

    /**
     * 是否为试运行。
     *
     * @return {@code true} 表示只校验不落库
     */
    public boolean isDryRun() {
        return Boolean.TRUE.equals(dryRun);
    }
}
