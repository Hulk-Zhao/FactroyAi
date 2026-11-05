package com.hengce.aps.service.importexport;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Excel 导入导出编排：模板下载、预览校验、执行落库与幂等令牌。
 *
 * <p>契约 C26：9 类实体共用模板/解析/错误定位/幂等管线；错误行统一为
 * {@code {row, field, code, message}}，单文件 5MB、单次 5000 行以内。</p>
 */
@Service
public class ImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportService.class);

    private static final int TOKEN_MIN_LENGTH = 8;

    private static final int TOKEN_MAX_LENGTH = 128;

    private final ExcelTemplateWriter templateWriter;

    private final ExcelRowReader rowReader;

    private final ImportIdempotencyStore idempotencyStore;

    private final Map<ImportEntity, EntityImportHandler> handlers = new EnumMap<>(ImportEntity.class);

    /**
     * 构造导入编排服务。
     *
     * @param templateWriter 模板生成器
     * @param rowReader Excel 读取器
     * @param idempotencyStore 幂等令牌存储
     * @param handlerBeans 已接入的实体导入器
     */
    public ImportService(ExcelTemplateWriter templateWriter, ExcelRowReader rowReader,
                         ImportIdempotencyStore idempotencyStore, List<EntityImportHandler> handlerBeans) {
        this.templateWriter = templateWriter;
        this.rowReader = rowReader;
        this.idempotencyStore = idempotencyStore;
        for (EntityImportHandler handler : handlerBeans) {
            handlers.put(handler.entity(), handler);
        }
    }

    /**
     * 生成实体模板。
     *
     * @param entity 实体
     * @return xlsx 字节
     */
    public byte[] template(ImportEntity entity) {
        return templateWriter.write(entity);
    }

    /**
     * 模板文件名。
     *
     * @param entity 实体
     * @return 文件名
     */
    public String templateFileName(ImportEntity entity) {
        return templateWriter.fileName(entity);
    }

    /**
     * 导入入口：预览只校验，执行在全部行通过后落库。
     *
     * @param entity 实体
     * @param mode 模式
     * @param token 幂等令牌，可为空
     * @param input 文件流
     * @param size 文件字节数
     * @return 导入结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportResult importFile(ImportEntity entity, ImportMode mode, String token,
                                   InputStream input, long size) {
        if (size <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "导入文件不能为空");
        }
        if (size > ImportLimits.MAX_FILE_BYTES) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "导入文件不能超过 " + (ImportLimits.MAX_FILE_BYTES / 1024 / 1024) + "MB");
        }
        String normalizedToken = normalizeToken(token);
        if (mode == ImportMode.EXECUTE && normalizedToken != null) {
            ImportResult replayed = idempotencyStore.find(tenantId(), entity.slug(), normalizedToken);
            if (replayed != null) {
                return replay(replayed);
            }
        }
        EntityImportHandler handler = handlerFor(entity);
        ExcelRowReader.ReadResult readResult;
        try {
            readResult = rowReader.read(input, entity);
        } catch (ImportRowException exception) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, exception.getMessage());
        }
        List<ImportError> errors = new ArrayList<>(readResult.errors());
        if (errors.size() > ImportLimits.MAX_ERRORS) {
            errors = errors.subList(0, ImportLimits.MAX_ERRORS);
        }
        if (!errors.isEmpty()) {
            return buildResult(entity, mode, readResult.rows().size(), 0, readResult.rows().size(), 0, 0, 0,
                    false, false, errors, List.of());
        }
        if (readResult.rows().size() > ImportLimits.MAX_ROWS) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "单次导入不能超过 " + ImportLimits.MAX_ROWS + " 行");
        }

        List<Map<String, Object>> normalizedRows = new ArrayList<>();
        for (ExcelRowReader.RowData row : readResult.rows()) {
            try {
                normalizedRows.add(handler.validate(row.values()));
            } catch (ImportRowException exception) {
                addError(errors, row.rowNumber(), exception.getField(), exception.getCode(), exception.getMessage());
            } catch (BizException exception) {
                addError(errors, row.rowNumber(), "", exception.getErrorCode().getCode(), exception.getMessage());
            } catch (RuntimeException exception) {
                LOGGER.warn("导入行校验异常 entity={} row={}", entity.slug(), row.rowNumber(), exception);
                addError(errors, row.rowNumber(), "", ErrorCode.INTERNAL_ERROR.getCode(), "行校验失败");
            }
        }
        List<Map<String, Object>> preview = normalizedRows.stream()
                .limit(ImportLimits.MAX_PREVIEW_ROWS)
                .toList();
        int invalidRows = readResult.rows().size() - normalizedRows.size();
        if (mode == ImportMode.PREVIEW) {
            return buildResult(entity, mode, readResult.rows().size(), normalizedRows.size(), invalidRows,
                    0, 0, 0, false, false, errors, preview);
        }
        if (!errors.isEmpty()) {
            return buildResult(entity, mode, readResult.rows().size(), normalizedRows.size(), invalidRows,
                    0, 0, 0, false, false, errors, preview);
        }
        if (!handler.executable()) {
            throw new BizException(ErrorCode.NOT_IMPLEMENTED, entity.label() + "导入执行尚未接入");
        }

        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        List<EntityImportHandler.WriteOutcome> outcomes;
        try {
            outcomes = handler.writeRows(normalizedRows);
        } catch (ImportRowException exception) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, exception.getMessage());
        }
        for (EntityImportHandler.WriteOutcome outcome : outcomes) {
            if (outcome.inserted()) {
                inserted++;
            } else if (outcome.updated()) {
                updated++;
            } else {
                skipped++;
            }
        }
        ImportResult result = buildResult(entity, mode, readResult.rows().size(), normalizedRows.size(),
                invalidRows, inserted, updated, skipped, true, false, errors, preview);
        if (normalizedToken != null) {
            idempotencyStore.save(tenantId(), entity.slug(), normalizedToken, result);
        }
        return result;
    }

    /**
     * 解析 URL 中的实体 slug。
     *
     * @param slug 实体标识
     * @return 实体
     * @throws BizException 未知实体
     */
    public ImportEntity resolveEntity(String slug) {
        ImportEntity entity = ImportEntity.fromSlug(slug);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "未知实体: " + slug);
        }
        return entity;
    }

    private EntityImportHandler handlerFor(ImportEntity entity) {
        EntityImportHandler handler = handlers.get(entity);
        if (handler == null) {
            throw new BizException(ErrorCode.NOT_IMPLEMENTED, entity.label() + "导入尚未接入");
        }
        return handler;
    }

    /**
     * 校验并归一化幂等令牌，供同步与异步导入共用。
     *
     * @param token 原始令牌，可为空
     * @return 归一化令牌；空白返回 {@code null}
     * @throws BizException 长度不在 8-128 位
     */
    public String normalizeToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.length() < TOKEN_MIN_LENGTH || trimmed.length() > TOKEN_MAX_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "X-Import-Token 需为 " + TOKEN_MIN_LENGTH + "-" + TOKEN_MAX_LENGTH + " 位");
        }
        return trimmed;
    }

    private ImportResult replay(ImportResult source) {
        return new ImportResult(source.entity(), source.mode(), source.totalRows(), source.validRows(),
                source.invalidRows(), source.inserted(), source.updated(), source.skipped(), source.committed(),
                true, source.errors(), source.preview());
    }

    private ImportResult buildResult(ImportEntity entity, ImportMode mode, int totalRows, int validRows,
                                     int invalidRows, int inserted, int updated, int skipped, boolean committed,
                                     boolean replayed, List<ImportError> errors, List<Map<String, Object>> preview) {
        return new ImportResult(entity.slug(), mode, totalRows, validRows, invalidRows, inserted, updated,
                skipped, committed, replayed, errors, preview);
    }

    private void addError(List<ImportError> errors, int row, String field, int code, String message) {
        if (errors.size() < ImportLimits.MAX_ERRORS) {
            errors.add(new ImportError(row, field, code, message));
        }
    }

    private String tenantId() {
        return TenantContext.getTenantId().toString();
    }
}
