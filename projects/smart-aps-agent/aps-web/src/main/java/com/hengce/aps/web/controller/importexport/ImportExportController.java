package com.hengce.aps.web.controller.importexport;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.importexport.EntityDataExporter;
import com.hengce.aps.service.importexport.ExcelDataWriter;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportJobService;
import com.hengce.aps.service.importexport.ImportJobView;
import com.hengce.aps.service.importexport.ImportLimits;
import com.hengce.aps.service.importexport.ImportMode;
import com.hengce.aps.service.importexport.ImportResult;
import com.hengce.aps.service.importexport.ImportService;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Excel 导入导出通用接口，契约 C26（M2-BD-05）与 C38/C39（M3-W9）。
 *
 * <p>实体共用同一组端点：{@code GET /{entity}/import/template} 下载模板，
 * {@code POST /{entity}/import} 预览、执行或异步受理。错误行统一返回 {@code {row, field, code, message}}。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "导入导出", description = "实体 Excel 模板下载与导入（C26/C38/C39）")
public class ImportExportController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ImportService importService;

    private final EntityDataExporter entityDataExporter;

    private final ImportJobService importJobService;

    /**
     * 构造导入导出控制器。
     *
     * @param importService 导入服务
     * @param entityDataExporter 导出服务
     * @param importJobService 异步导入服务
     */
    public ImportExportController(ImportService importService, EntityDataExporter entityDataExporter,
                                  ImportJobService importJobService) {
        this.importService = importService;
        this.entityDataExporter = entityDataExporter;
        this.importJobService = importJobService;
    }

    /**
     * 下载实体导入模板。
     *
     * @param entity 实体 slug
     * @return xlsx 文件
     */
    @Operation(summary = "下载导入模板", description = "状态：已实现。返回可直接被 Excel 打开的 xlsx。")
    @GetMapping({"/{entity}/import/template", "/import/{entity}/template"})
    public ResponseEntity<byte[]> template(@PathVariable String entity) {
        ImportEntity resolved = importService.resolveEntity(entity);
        byte[] content = importService.template(resolved);
        String fileName = importService.templateFileName(resolved);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Template-Filename", URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE))
                .body(content);
    }

    /**
     * 预览、执行或异步受理导入。
     *
     * @param entity 实体 slug
     * @param mode preview=预览，execute=执行，async=异步受理
     * @param token 幂等令牌，执行/异步模式下重复提交同一令牌返回首次结果
     * @param file xlsx 文件
     * @return 同步模式返回 {@code Result<ImportResult>}；异步模式返回 202 + {@code ImportJobView}
     */
    @Operation(summary = "导入 Excel",
            description = "状态：已实现。mode=preview 只校验；mode=execute 全部行通过后落库；"
                    + "mode=async 受理后返回 202，后台执行并可通过任务端点轮询。"
                    + "兼容路径 /import/{entity} 用于绕开 M1 遗留的 /materials/import 占位映射。")
    @RequiresPermission("MD_WRITE")
    @PostMapping(value = {"/{entity}/import", "/import/{entity}"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importFile(
            @PathVariable String entity,
            @Parameter(description = "preview/execute/async") @RequestParam(defaultValue = "preview") String mode,
            @Parameter(description = "幂等令牌，8-128 位") @RequestHeader(value = "X-Import-Token",
                    required = false) String token,
            @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        ImportEntity resolved = importService.resolveEntity(entity);
        if ("async".equalsIgnoreCase(mode)) {
            if (file == null || file.isEmpty()) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "导入文件不能为空");
            }
            if (file.getSize() > ImportLimits.MAX_FILE_BYTES) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "导入文件不能超过 " + (ImportLimits.MAX_FILE_BYTES / 1024 / 1024) + "MB");
            }
            ImportJobView job = importJobService.submit(resolved, token, file.getOriginalFilename(),
                    file.getBytes());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
        }
        ImportMode importMode = parseMode(mode);
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "导入文件不能为空");
        }
        ImportResult result = importService.importFile(resolved, importMode, token, file.getInputStream(),
                file.getSize());
        return ResponseEntity.ok(Result.success(result));
    }

    /**
     * 查询异步导入任务。
     *
     * @param entity 实体 slug
     * @param jobId 任务标识
     * @return 任务视图
     */
    @Operation(summary = "查询异步导入任务", description = "状态：已实现。跨实体查询按 40400 处理。")
    @GetMapping("/{entity}/import/jobs/{jobId}")
    public ResponseEntity<ImportJobView> importJob(@PathVariable String entity,
                                                   @Parameter(description = "任务 UUID") @PathVariable UUID jobId) {
        ImportEntity resolved = importService.resolveEntity(entity);
        return ResponseEntity.ok(importJobService.find(resolved, jobId));
    }

    /**
     * 取消异步导入任务。
     *
     * @param entity 实体 slug
     * @param jobId 任务标识
     * @return 202 + 取消后的任务视图
     */
    @Operation(summary = "取消异步导入任务", description = "状态：已实现。仅 PENDING/RUNNING 可取消，其他状态 40901。")
    @RequiresPermission("MD_WRITE")
    @PostMapping("/{entity}/import/jobs/{jobId}/cancel")
    public ResponseEntity<ImportJobView> cancelImportJob(
            @PathVariable String entity,
            @Parameter(description = "任务 UUID") @PathVariable UUID jobId) {
        ImportEntity resolved = importService.resolveEntity(entity);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(importJobService.cancel(resolved, jobId));
    }

    /**
     * 导出实体数据。
     *
     * @param entity 实体 slug
     * @return xlsx 文件
     */
    @Operation(summary = "导出数据", description = "状态：已实现。列顺序与导入模板一致，可直接回填导入。")
    @GetMapping({"/{entity}/export", "/export/{entity}"})
    public ResponseEntity<byte[]> export(@PathVariable String entity) {
        ImportEntity resolved = importService.resolveEntity(entity);
        byte[] content = entityDataExporter.export(resolved);
        String fileName = ExcelDataWriter.fileName(resolved);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE))
                .body(content);
    }

    private ImportMode parseMode(String mode) {
        if (mode == null || mode.isBlank() || "preview".equalsIgnoreCase(mode)) {
            return ImportMode.PREVIEW;
        }
        if ("execute".equalsIgnoreCase(mode)) {
            return ImportMode.EXECUTE;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "mode 只支持 preview/execute/async");
    }
}
