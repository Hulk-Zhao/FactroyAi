package com.hengce.aps.solver.timefold.dependency;

import java.util.List;

/**
 * 依赖图构建结果。
 *
 * @param edges 依赖边
 * @param fallback 是否因缺少显式依赖而按 {@code operationIndex} 补了默认 FS/零滞后
 */
public record DependencyGraph(List<TaskDependency> edges, boolean fallback) {
}
