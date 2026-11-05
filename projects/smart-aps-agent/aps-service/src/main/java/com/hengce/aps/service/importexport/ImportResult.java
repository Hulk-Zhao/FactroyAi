package com.hengce.aps.service.importexport;

import java.util.List;
import java.util.Map;

/**
 * 导入结果，契约 C26。
 *
 * @param entity 实体 slug
 * @param mode 导入模式
 * @param totalRows 数据行总数（不含表头）
 * @param validRows 校验通过行数
 * @param invalidRows 校验失败行数
 * @param inserted 新增行数
 * @param updated 更新行数
 * @param skipped 已存在且无需变更的跳过行数
 * @param committed 是否已落库
 * @param replayed 是否命中幂等令牌并返回历史结果
 * @param errors 错误行，最多 {@link ImportLimits#MAX_ERRORS}
 * @param preview 规范化后的样例行（最多 {@link ImportLimits#MAX_PREVIEW_ROWS}）
 */
public record ImportResult(
        String entity,
        ImportMode mode,
        int totalRows,
        int validRows,
        int invalidRows,
        int inserted,
        int updated,
        int skipped,
        boolean committed,
        boolean replayed,
        List<ImportError> errors,
        List<Map<String, Object>> preview) {

    /**
     * 创建空结果。
     *
     * @param entity 实体
     * @param mode 模式
     * @return 空结果
     */
    public static ImportResult empty(ImportEntity entity, ImportMode mode) {
        return new ImportResult(entity.slug(), mode, 0, 0, 0, 0, 0, 0, false, false, List.of(), List.of());
    }
}
