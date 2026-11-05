package com.hengce.aps.solver.timefold.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 一道固化基准题：问题输入、预算、seed 与期望状态都锁在同一个 JSON 里。
 *
 * <p>{@link #fingerprint()} 是对整个 case.json 规范化后的 SHA256：题面、预算、seed
 * 任一项变化都会换指纹，跑分报告必须打印它。{@link #fileSha256()} 保留原始文件字节摘要，
 * 便于发现“只是改了缩进/键顺序”的无关变更。</p>
 *
 * @param caseId 题目编号（目录名必须一致）
 * @param kind 题目类别：SMOKE / FEASIBLE_MEDIUM / HARD_FEASIBLE / INFEASIBLE / LARGE_REF / INCREMENTAL
 * @param category 题面用途：为空表示回归门禁题；{@code stress-reference} 表示仅规模参考，默认不执行
 * @param sizeClass 声明档位：SMALL / MEDIUM / LARGE
 * @param tags 标签
 * @param seed 固定随机种子
 * @param budget 终止预算
 * @param spec 题面生成规格
 * @param expectation 期望（状态 / 硬分上限 / 期望证据编码 / 期望任务数）
 * @param fingerprint 规范化 JSON 的 SHA256
 * @param fileSha256 原始文件字节的 SHA256
 * @param source 文件路径
 */
record BenchCase(
        String caseId,
        String kind,
        String category,
        String sizeClass,
        List<String> tags,
        long seed,
        Budget budget,
        Map<String, Object> spec,
        Map<String, Object> expectation,
        String fingerprint,
        String fileSha256,
        Path source
) {

    private static final String CASE_FILE = "case.json";

    /**
     * 终止预算；{@code maximumStepCount > 0} 时才是确定性步数模式，否则为 SLA 墙钟模式。
     *
     * @param timeoutSeconds 墙钟上限（秒）
     * @param unimprovedSecondsSpentLimit 无改进秒数上限；{@code <=0} 表示不设
     * @param maximumStepCount 步数上限；{@code <=0} 表示不限
     */
    record Budget(int timeoutSeconds, int unimprovedSecondsSpentLimit, long maximumStepCount) {
    }

    /**
     * 从 case.json 加载题目。
     *
     * @param file case.json
     * @return 题目
     */
    static BenchCase load(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> root = Json.object(Json.parse(text));
            String caseId = Json.str(root, "caseId", null);
            if (caseId == null || caseId.isBlank()) {
                throw new IllegalArgumentException("缺少 caseId: " + file);
            }
            Map<String, Object> problem = Json.object(root.get("problem"));
            Map<String, Object> budgetMap = root.get("budget") instanceof Map<?, ?>
                    ? Json.object(root.get("budget")) : Map.of();
            Map<String, Object> expectation = root.get("expectation") instanceof Map<?, ?>
                    ? Json.object(root.get("expectation")) : Map.of();
            Budget budget = new Budget(
                    Json.intValue(budgetMap, "timeoutSeconds", 3),
                    Json.intValue(budgetMap, "unimprovedSecondsSpentLimit", 0),
                    Json.longValue(budgetMap, "maximumStepCount", 0L));
            return new BenchCase(
                    caseId,
                    Json.str(root, "kind", "UNKNOWN"),
                    Json.str(root, "category", ""),
                    Json.str(root, "sizeClass", "UNKNOWN"),
                    Json.stringList(root, "tags", List.of()),
                    Json.longValue(root, "seed", 0L),
                    budget,
                    problem,
                    expectation,
                    Json.sha256(Json.canonical(root)),
                    Json.sha256(text),
                    file);
        } catch (IOException exception) {
            throw new UncheckedIOException("读取题面失败: " + file, exception);
        }
    }

    /**
     * 扫描题面根目录下的 {@code <caseId>/case.json}，按目录名排序。
     *
     * @param casesDir 题面根目录
     * @return 题目列表
     */
    static List<BenchCase> loadAll(Path casesDir) {
        try (Stream<Path> dirs = Files.list(casesDir)) {
            return dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(dir -> dir.resolve(CASE_FILE))
                    .filter(Files::isRegularFile)
                    .map(BenchCase::load)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("扫描题面目录失败: " + casesDir, exception);
        }
    }
}
