package com.hengce.aps.web.solve;

import java.util.List;
import java.util.Locale;

/**
 * 真库验收测试的库名守卫：Flyway {@code clean} 会 drop 目标库全部对象，
 * 只有受控测试库才允许执行。
 *
 * <p>校验依据是数据库自报的真实库名（{@code SELECT current_database()}），
 * 不是测试配置或环境变量里的字符串，避免二者不一致时误删 dev/生产库。</p>
 */
final class RealDbTargetGuard {

    /**
     * 允许执行 Flyway clean 的库名前缀：CI/本地专用测试库与 M2 各条线测试库。
     */
    static final List<String> ALLOWED_PREFIXES = List.of("aps_test", "aps_m2_");

    private RealDbTargetGuard() {
    }

    /**
     * 断言当前真实库名属于受控测试库，否则立即终止，绝不继续执行 clean。
     *
     * @param databaseName {@code SELECT current_database()} 返回的真实库名
     * @throws IllegalStateException 库名为空或不在白名单前缀内
     */
    static void requireCleanAllowed(String databaseName) {
        String normalized = databaseName == null ? "" : databaseName.toLowerCase(Locale.ROOT);
        boolean allowed = ALLOWED_PREFIXES.stream().anyMatch(normalized::startsWith);
        if (!allowed) {
            throw new IllegalStateException("拒绝在库 " + databaseName
                    + " 上执行 Flyway clean：允许的库名前缀为 " + ALLOWED_PREFIXES
                    + "；真库测试禁止指向 dev/生产库");
        }
    }
}
