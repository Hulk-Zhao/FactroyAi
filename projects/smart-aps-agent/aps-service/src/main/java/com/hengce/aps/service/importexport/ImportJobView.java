package com.hengce.aps.service.importexport;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 异步导入任务视图，契约 C39 {@code ImportJobView}。
 *
 * @param jobId 任务标识
 * @param entity 实体 slug
 * @param mode 固定为 {@code async}
 * @param status 状态机取值
 * @param totalRows 数据行总数（受理时为 0，执行后回填）
 * @param processedRows 已处理行数
 * @param inserted 新增行数
 * @param updated 更新行数
 * @param skipped 跳过行数
 * @param invalidRows 校验失败行数
 * @param errors 错误行，最多 {@link ImportLimits#MAX_ERRORS}
 * @param committed 是否已提交落库；{@code FAILED} 必为 {@code false}
 * @param contentHash 内容摘要 {@code sha256:<hex>}
 * @param createdAt 受理时间
 * @param startedAt 开始执行时间，可为空
 * @param finishedAt 终态时间，可为空
 */
public record ImportJobView(
        UUID jobId,
        String entity,
        String mode,
        ImportJobStatus status,
        int totalRows,
        int processedRows,
        int inserted,
        int updated,
        int skipped,
        int invalidRows,
        List<ImportError> errors,
        boolean committed,
        String contentHash,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {

    /** 异步模式固定值。 */
    public static final String MODE_ASYNC = "async";
}
