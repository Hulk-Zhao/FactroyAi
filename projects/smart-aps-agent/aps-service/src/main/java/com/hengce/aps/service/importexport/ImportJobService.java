package com.hengce.aps.service.importexport;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 异步大文件导入编排（C39）：受理返回 202、后台执行、状态查询与取消。
 *
 * <p>失败整批回滚：批次写入与最终状态提交在同一个事务内完成；若执行中被取消，
 * 通过回滚标记放弃本批写入，任务保持 {@code CANCELLED}。</p>
 */
@Service
public class ImportJobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportJobService.class);

    private final ImportService importService;

    private final ImportJobRepository repository;

    private final ImportJobTokenStore tokenStore;

    private final ImportJobExecutor executor;

    private final ExcelRowReader rowReader;

    private final TransactionTemplate transactionTemplate;

    /**
     * 构造异步导入服务。
     *
     * @param importService 同步导入编排（复用同一套校验/落库语义）
     * @param repository 任务仓储
     * @param tokenStore 令牌映射
     * @param executor 执行池
     * @param rowReader Excel 读取器（受理时做结构与行数校验）
     * @param transactionManager 事务管理器
     */
    public ImportJobService(ImportService importService, ImportJobRepository repository,
                            ImportJobTokenStore tokenStore, ImportJobExecutor executor,
                            ExcelRowReader rowReader, PlatformTransactionManager transactionManager) {
        this.importService = importService;
        this.repository = repository;
        this.tokenStore = tokenStore;
        this.executor = executor;
        this.rowReader = rowReader;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 受理异步导入：结构、大小与行数同步校验，通过后立即返回 {@code PENDING} 任务。
     *
     * @param entity 实体
     * @param token 幂等令牌，可为空
     * @param fileName 原始文件名，可为空
     * @param content 文件字节
     * @return 任务视图
     */
    public ImportJobView submit(ImportEntity entity, String token, String fileName, byte[] content) {
        UUID tenantId = TenantContext.getTenantId();
        String normalizedToken = importService.normalizeToken(token);
        if (content == null || content.length == 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "导入文件不能为空");
        }
        if (content.length > ImportLimits.MAX_FILE_BYTES) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "导入文件不能超过 " + (ImportLimits.MAX_FILE_BYTES / 1024 / 1024) + "MB");
        }
        ExcelRowReader.ReadResult readResult;
        try {
            readResult = rowReader.read(new ByteArrayInputStream(content), entity);
        } catch (ImportRowException exception) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, exception.getMessage());
        }
        if (!readResult.errors().isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, readResult.errors().get(0).message());
        }
        if (readResult.rows().size() > ImportLimits.MAX_ROWS) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "单次导入不能超过 " + ImportLimits.MAX_ROWS + " 行");
        }

        String contentHash = contentHash(tenantId, entity, content);
        if (normalizedToken != null) {
            ImportJobTokenStore.TokenEntry entry = tokenStore.find(tenantId.toString(), entity.slug(),
                    normalizedToken);
            if (entry != null && entry.contentHash().equals(contentHash)) {
                ImportJobView replayed = repository.find(tenantId, entry.jobId());
                if (replayed != null) {
                    return replayed;
                }
                tokenStore.remove(tenantId.toString(), entity.slug(), normalizedToken);
            }
        }
        ImportJobView inFlight = repository.findInFlight(tenantId, entity.slug(), contentHash);
        if (inFlight != null) {
            rememberToken(tenantId, entity, normalizedToken, inFlight.jobId(), contentHash);
            return inFlight;
        }

        UUID jobId = UUID.randomUUID();
        try {
            repository.insert(jobId, tenantId, entity, contentHash, fileName, content.length,
                    AuditContext.getCurrentUserId());
        } catch (DataIntegrityViolationException exception) {
            ImportJobView concurrent = repository.findInFlight(tenantId, entity.slug(), contentHash);
            if (concurrent != null) {
                rememberToken(tenantId, entity, normalizedToken, concurrent.jobId(), contentHash);
                return concurrent;
            }
            throw new BizException(ErrorCode.INTERNAL_ERROR, "导入任务创建失败");
        }
        rememberToken(tenantId, entity, normalizedToken, jobId, contentHash);
        executor.submit(() -> runJob(jobId, tenantId, entity, content));
        ImportJobView view = repository.find(tenantId, jobId);
        if (view == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "导入任务读取失败");
        }
        return view;
    }

    /**
     * 查询任务；实体不匹配按不存在处理。
     *
     * @param entity 实体
     * @param jobId 任务标识
     * @return 任务视图
     */
    public ImportJobView find(ImportEntity entity, UUID jobId) {
        UUID tenantId = TenantContext.getTenantId();
        ImportJobView view = repository.find(tenantId, jobId);
        if (view == null || !view.entity().equals(entity.slug())) {
            throw new BizException(ErrorCode.NOT_FOUND, "导入任务不存在: " + jobId);
        }
        return view;
    }

    /**
     * 取消任务：仅 {@code PENDING}/{@code RUNNING} 可取消。
     *
     * @param entity 实体
     * @param jobId 任务标识
     * @return 取消后的任务视图
     */
    public ImportJobView cancel(ImportEntity entity, UUID jobId) {
        find(entity, jobId);
        UUID tenantId = TenantContext.getTenantId();
        if (!repository.cancel(tenantId, jobId)) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "导入任务已结束（" + repository.status(tenantId, jobId) + "），无法取消");
        }
        return repository.find(tenantId, jobId);
    }

    private void runJob(UUID jobId, UUID tenantId, ImportEntity entity, byte[] content) {
        TenantContext.setTenantId(tenantId);
        try {
            if (!repository.markRunning(tenantId, jobId)) {
                return;
            }
            ImportResult result;
            try {
                result = transactionTemplate.execute(status -> {
                    if (isCancelled(tenantId, jobId)) {
                        status.setRollbackOnly();
                        return null;
                    }
                    ImportResult batch = importService.importFile(entity, ImportMode.EXECUTE, null,
                            new ByteArrayInputStream(content), content.length);
                    if (isCancelled(tenantId, jobId)) {
                        status.setRollbackOnly();
                        return null;
                    }
                    if (!batch.committed()) {
                        status.setRollbackOnly();
                        return batch;
                    }
                    boolean finalized = repository.finishSuccess(tenantId, jobId, batch.totalRows(),
                            batch.totalRows(), batch.inserted(), batch.updated(), batch.skipped(),
                            batch.invalidRows());
                    if (!finalized) {
                        status.setRollbackOnly();
                        return null;
                    }
                    return batch;
                });
            } catch (RuntimeException exception) {
                markFailed(tenantId, jobId, exception);
                return;
            }
            if (result == null) {
                LOGGER.info("异步导入已取消 jobId={} entity={}", jobId, entity.slug());
                return;
            }
            if (!result.committed()) {
                repository.finishFailed(tenantId, jobId, result.totalRows(), result.validRows(),
                        result.invalidRows(), result.errors());
            }
        } finally {
            TenantContext.clear();
        }
    }

    private void markFailed(UUID tenantId, UUID jobId, RuntimeException exception) {
        int code = ErrorCode.INTERNAL_ERROR.getCode();
        String message = "导入任务执行失败";
        if (exception instanceof BizException bizException) {
            code = bizException.getErrorCode().getCode();
            message = bizException.getMessage();
        }
        LOGGER.error("异步导入失败 jobId={}", jobId, exception);
        repository.finishFailed(tenantId, jobId, 0, 0, 0,
                List.of(new ImportError(0, "file", code, message)));
    }

    private boolean isCancelled(UUID tenantId, UUID jobId) {
        return ImportJobStatus.CANCELLED.name().equals(repository.status(tenantId, jobId));
    }

    private void rememberToken(UUID tenantId, ImportEntity entity, String token, UUID jobId,
                               String contentHash) {
        if (token != null) {
            tokenStore.save(tenantId.toString(), entity.slug(), token, jobId, contentHash);
        }
    }

    private String contentHash(UUID tenantId, ImportEntity entity, byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((tenantId + "|" + entity.slug() + "|").getBytes(StandardCharsets.UTF_8));
            digest.update(content);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }
}
