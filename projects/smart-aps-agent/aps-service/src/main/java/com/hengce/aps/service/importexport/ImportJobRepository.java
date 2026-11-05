package com.hengce.aps.service.importexport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code import_job} 持久化（V12）：受理、查询、状态流转与取消。
 */
@Component
public class ImportJobRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportJobRepository.class);

    private static final String COLUMNS = "id, entity, status, content_hash, total_rows, processed_rows, "
            + "inserted, updated, skipped, invalid_rows, errors, committed, created_at, started_at, finished_at";

    private static final TypeReference<List<ImportError>> ERRORS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    /**
     * 构造任务仓储。
     *
     * @param jdbcTemplate JDBC 模板
     * @param objectMapper JSON 序列化器（errors JSONB 读写）
     */
    public ImportJobRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 受理任务，初始状态 {@code PENDING}、计数全 0。
     *
     * @param jobId 任务标识
     * @param tenantId 租户
     * @param entity 实体
     * @param contentHash 内容摘要
     * @param fileName 原始文件名
     * @param fileBytes 文件字节数
     * @param createdBy 操作人，可为空
     */
    public void insert(UUID jobId, UUID tenantId, ImportEntity entity, String contentHash,
                       String fileName, long fileBytes, UUID createdBy) {
        jdbcTemplate.update("""
                INSERT INTO import_job (id, tenant_id, entity, status, content_hash, file_name, file_bytes,
                    created_by)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?)
                """, jobId, tenantId, entity.slug(), contentHash, fileName, fileBytes, createdBy);
    }

    /**
     * 按任务标识查询。
     *
     * @param tenantId 租户
     * @param jobId 任务标识
     * @return 任务视图；不存在返回 {@code null}
     */
    public ImportJobView find(UUID tenantId, UUID jobId) {
        List<ImportJobView> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM import_job WHERE id = ? AND tenant_id = ?",
                (rs, index) -> map(rs), jobId, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查询同内容在途任务（{@code PENDING}/{@code RUNNING}），用于防重复受理。
     *
     * @param tenantId 租户
     * @param entity 实体 slug
     * @param contentHash 内容摘要
     * @return 在途任务；无则返回 {@code null}
     */
    public ImportJobView findInFlight(UUID tenantId, String entity, String contentHash) {
        List<ImportJobView> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM import_job WHERE tenant_id = ? AND entity = ? AND content_hash = ? "
                        + "AND status IN ('PENDING', 'RUNNING') ORDER BY created_at ASC LIMIT 1",
                (rs, index) -> map(rs), tenantId, entity, contentHash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 读取任务状态。
     *
     * @param tenantId 租户
     * @param jobId 任务标识
     * @return 状态名；任务不存在或不属于该租户返回 {@code null}
     */
    public String status(UUID tenantId, UUID jobId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT status FROM import_job WHERE id = ? AND tenant_id = ?",
                (rs, index) -> rs.getString(1), jobId, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * {@code PENDING -> RUNNING}，同时记录开始时间。
     *
     * @param tenantId 租户
     * @param jobId 任务标识
     * @return {@code true} 表示本次成功进入执行态
     */
    public boolean markRunning(UUID tenantId, UUID jobId) {
        return jdbcTemplate.update(
                "UPDATE import_job SET status = 'RUNNING', started_at = now() "
                        + "WHERE id = ? AND tenant_id = ? AND status = 'PENDING'",
                jobId, tenantId) > 0;
    }

    /**
     * {@code RUNNING -> SUCCEEDED}；若已被取消则返回 {@code false}，调用方据此回滚本批写入。
     *
     * @param tenantId 租户
     * @param jobId 任务标识
     * @param totalRows 数据行总数
     * @param processedRows 已处理行数
     * @param inserted 新增行数
     * @param updated 更新行数
     * @param skipped 跳过行数
     * @param invalidRows 校验失败行数
     * @return {@code true} 表示本次成功提交
     */
    public boolean finishSuccess(UUID tenantId, UUID jobId, int totalRows, int processedRows, int inserted,
                                 int updated, int skipped, int invalidRows) {
        return jdbcTemplate.update("""
                UPDATE import_job SET status = 'SUCCEEDED', total_rows = ?, processed_rows = ?, inserted = ?,
                    updated = ?, skipped = ?, invalid_rows = ?, errors = '[]'::jsonb, committed = true,
                    finished_at = now()
                WHERE id = ? AND tenant_id = ? AND status = 'RUNNING'
                """, totalRows, processedRows, inserted, updated, skipped, invalidRows, jobId, tenantId) > 0;
    }

    /**
     * 执行失败，整批回滚后落 {@code FAILED}（不覆盖已取消的终态）。
     *
     * @param tenantId 租户
     * @param jobId 任务标识
     * @param totalRows 数据行总数
     * @param processedRows 已处理行数
     * @param invalidRows 校验失败行数
     * @param errors 错误行，最多 {@link ImportLimits#MAX_ERRORS}
     * @return {@code true} 表示本次写入终态
     */
    public boolean finishFailed(UUID tenantId, UUID jobId, int totalRows, int processedRows, int invalidRows,
                                List<ImportError> errors) {
        List<ImportError> limited = errors.size() > ImportLimits.MAX_ERRORS
                ? errors.subList(0, ImportLimits.MAX_ERRORS) : errors;
        return jdbcTemplate.update("""
                UPDATE import_job SET status = 'FAILED', total_rows = ?, processed_rows = ?, invalid_rows = ?,
                    errors = ?::jsonb, committed = false, finished_at = now()
                WHERE id = ? AND tenant_id = ? AND status IN ('PENDING', 'RUNNING')
                """, totalRows, processedRows, invalidRows, json(limited), jobId, tenantId) > 0;
    }

    /**
     * 取消任务：仅 {@code PENDING}/{@code RUNNING} 可取消。
     *
     * @param tenantId 租户
     * @param jobId 任务标识
     * @return {@code true} 表示本次取消成功
     */
    public boolean cancel(UUID tenantId, UUID jobId) {
        return jdbcTemplate.update("""
                UPDATE import_job SET status = 'CANCELLED', finished_at = now()
                WHERE id = ? AND tenant_id = ? AND status IN ('PENDING', 'RUNNING')
                """, jobId, tenantId) > 0;
    }

    private ImportJobView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ImportJobView(
                (UUID) rs.getObject("id"),
                rs.getString("entity"),
                ImportJobView.MODE_ASYNC,
                ImportJobStatus.valueOf(rs.getString("status")),
                rs.getInt("total_rows"),
                rs.getInt("processed_rows"),
                rs.getInt("inserted"),
                rs.getInt("updated"),
                rs.getInt("skipped"),
                rs.getInt("invalid_rows"),
                errors(rs.getString("errors")),
                rs.getBoolean("committed"),
                rs.getString("content_hash"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class));
    }

    private List<ImportError> errors(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, ERRORS_TYPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            LOGGER.warn("import_job.errors 解析失败，按空错误列表返回", exception);
            return List.of();
        }
    }

    private String json(List<ImportError> errors) {
        try {
            return objectMapper.writeValueAsString(errors);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("序列化导入错误失败", exception);
        }
    }
}
