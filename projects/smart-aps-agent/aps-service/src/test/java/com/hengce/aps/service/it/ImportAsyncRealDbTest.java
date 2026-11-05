package com.hengce.aps.service.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.importexport.EntityDataExporter;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportJobService;
import com.hengce.aps.service.importexport.ImportJobStatus;
import com.hengce.aps.service.importexport.ImportJobView;
import com.hengce.aps.service.importexport.ImportLimits;
import com.hengce.aps.service.importexport.ImportMode;
import com.hengce.aps.service.importexport.ImportResult;
import com.hengce.aps.service.importexport.ImportService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * M3-W9 真库集成测试：C38 customers 四段（模板/预览/执行/导出）与 C39 异步导入全链路。
 *
 * <p>覆盖：202 受理与轮询终态、失败整批回滚、取消生效、同 token+contentHash 重放只生效一次。
 * 使用 {@code APS_TEST_DB_ENABLED=true} 指向临时库；Flyway 在该库上跑到 V12。</p>
 */
@TestPropertySource(properties = "spring.flyway.enabled=false")
class ImportAsyncRealDbTest extends AbstractRealDbIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private ImportService importService;

    @Autowired
    private ImportJobService importJobService;

    @Autowired
    private EntityDataExporter entityDataExporter;

    private final List<UUID> jobIds = new ArrayList<>();

    @BeforeAll
    static void migrateToLatest() {
        if (!RealDbEnvironment.enabled()) {
            return;
        }
        Flyway.configure()
                .dataSource(RealDbEnvironment.jdbcUrl(), RealDbEnvironment.username(),
                        RealDbEnvironment.password())
                .locations("filesystem:../db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void requireRealDb() {
        Assumptions.assumeTrue(RealDbEnvironment.enabled(), "未启用真库测试：APS_TEST_DB_ENABLED");
    }

    @AfterEach
    void cleanupImportData() {
        for (UUID jobId : jobIds) {
            jdbc.update("DELETE FROM import_job WHERE id = ?", jobId);
        }
        jdbc.update("DELETE FROM customer WHERE code LIKE ?", RUN_PREFIX + "%");
    }

    @Test
    @DisplayName("C26 上限：常量 5MB，同步/异步超限均返回 42200")
    void importLimitsAreFiveMegabytes() {
        byte[] oversized = new byte[(int) ImportLimits.MAX_FILE_BYTES + 1];
        assertThat(ImportLimits.MAX_FILE_BYTES).isEqualTo(5L * 1024 * 1024);
        assertThat(ImportLimits.MAX_ROWS).isEqualTo(5000);

        assertThatThrownBy(() -> importService.importFile(ImportEntity.CUSTOMERS, ImportMode.PREVIEW, null,
                new ByteArrayInputStream(oversized), oversized.length))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);
        assertThatThrownBy(() -> importJobService.submit(ImportEntity.CUSTOMERS, null, "big.xlsx", oversized))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);
        evidence("C26 上限: MAX_FILE_BYTES=" + ImportLimits.MAX_FILE_BYTES + " (5MB) MAX_ROWS="
                + ImportLimits.MAX_ROWS + " 超限(5MB+1B) 同步/异步均 42200");
    }

    @Test
    @DisplayName("C38 customers：模板下载、预览、执行、导出四段")
    void customersImportExportFourSegments() throws IOException {
        byte[] template = importService.template(ImportEntity.CUSTOMERS);
        String templateName = importService.templateFileName(ImportEntity.CUSTOMERS);
        List<String> headers = headers(template);
        assertThat(templateName).isEqualTo("customers-import-template.xlsx");
        assertThat(headers).containsExactly("客户编码 *", "客户名称 *", "优先级", "交付提前期(天)",
                "付款条件", "是否黑名单");
        evidence("C38 模板: file=" + templateName + " bytes=" + template.length + " headers=" + headers);

        String codeA = tag("CUS-A");
        String codeB = tag("CUS-B");
        byte[] file = workbook(template, List.of(
                new String[]{codeA, "客户A", "VIP", "5", "月结30天", "false"},
                new String[]{codeB, "客户B", "NORMAL", "7", "", "true"}));

        ImportResult preview = importService.importFile(ImportEntity.CUSTOMERS, ImportMode.PREVIEW, null,
                new ByteArrayInputStream(file), file.length);
        assertThat(preview.mode()).isEqualTo(ImportMode.PREVIEW);
        assertThat(preview.totalRows()).isEqualTo(2);
        assertThat(preview.validRows()).isEqualTo(2);
        assertThat(preview.committed()).isFalse();
        assertThat(preview.errors()).isEmpty();
        assertThat(count("SELECT count(*) FROM customer WHERE code LIKE ?", RUN_PREFIX + "%")).isZero();
        evidence("C38 预览: totalRows=" + preview.totalRows() + " validRows=" + preview.validRows()
                + " committed=" + preview.committed() + " 落库行数=0");

        ImportResult execute = importService.importFile(ImportEntity.CUSTOMERS, ImportMode.EXECUTE, null,
                new ByteArrayInputStream(file), file.length);
        assertThat(execute.committed()).isTrue();
        assertThat(execute.inserted()).isEqualTo(2);
        assertThat(execute.updated()).isZero();
        assertThat(count("SELECT count(*) FROM customer WHERE code LIKE ?", RUN_PREFIX + "%")).isEqualTo(2);
        evidence("C38 执行: committed=true inserted=" + execute.inserted() + " updated=" + execute.updated()
                + " 落库行数=2");

        ImportResult second = importService.importFile(ImportEntity.CUSTOMERS, ImportMode.EXECUTE, null,
                new ByteArrayInputStream(file), file.length);
        assertThat(second.committed()).isTrue();
        assertThat(second.updated()).isEqualTo(2);
        evidence("C38 二次执行(按 code upsert): inserted=" + second.inserted() + " updated="
                + second.updated());

        byte[] exported = entityDataExporter.export(ImportEntity.CUSTOMERS);
        List<List<String>> exportedRows = dataRows(exported);
        List<String> exportedCodes = exportedRows.stream().map(row -> row.get(0)).toList();
        assertThat(exportedCodes).contains(codeA, codeB);
        List<String> rowA = exportedRows.stream().filter(row -> row.get(0).equals(codeA)).findFirst()
                .orElseThrow();
        assertThat(rowA).containsExactly(codeA, "客户A", "VIP", "5", "月结30天", "false");
        evidence("C38 导出: bytes=" + exported.length + " rows=" + exportedRows.size()
                + " 命中=" + rowA);
    }

    @Test
    @DisplayName("C39 异步成功：202 受理 -> 轮询 SUCCEEDED，同 token+contentHash 重放只生效一次")
    void asyncImportSucceedsAndReplayIsIdempotent() throws IOException {
        byte[] template = importService.template(ImportEntity.CUSTOMERS);
        String code = tag("CUS-ASYNC");
        byte[] file = workbook(template, List.<String[]>of(
                new String[]{code, "异步客户", "VIP", "3", "月结60天", "false"}));
        String token = "W9-ASYNC-TOKEN-1";

        ImportJobView accepted = importJobService.submit(ImportEntity.CUSTOMERS, token, "customers.xlsx", file);
        jobIds.add(accepted.jobId());
        assertThat(accepted.mode()).isEqualTo("async");
        assertThat(accepted.jobId()).isNotNull();
        evidence("C39 受理: jobId=" + accepted.jobId() + " status=" + accepted.status()
                + " mode=" + accepted.mode() + " contentHash=" + accepted.contentHash());

        ImportJobView terminal = awaitTerminal(accepted.jobId());
        assertThat(terminal.status()).isEqualTo(ImportJobStatus.SUCCEEDED);
        assertThat(terminal.committed()).isTrue();
        assertThat(terminal.totalRows()).isEqualTo(1);
        assertThat(terminal.processedRows()).isEqualTo(1);
        assertThat(terminal.inserted()).isEqualTo(1);
        assertThat(terminal.finishedAt()).isNotNull();
        assertThat(count("SELECT count(*) FROM customer WHERE code = ?", code)).isEqualTo(1);
        evidence("C39 终态: status=" + terminal.status() + " total=" + terminal.totalRows()
                + " inserted=" + terminal.inserted() + " updated=" + terminal.updated()
                + " committed=" + terminal.committed());

        ImportJobView replay = importJobService.submit(ImportEntity.CUSTOMERS, token, "customers.xlsx", file);
        assertThat(replay.jobId()).isEqualTo(accepted.jobId());
        assertThat(replay.status()).isEqualTo(ImportJobStatus.SUCCEEDED);
        assertThat(count("SELECT count(*) FROM import_job WHERE content_hash = ?",
                terminal.contentHash())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM customer WHERE code = ?", code)).isEqualTo(1);
        evidence("C39 重放: jobId 相同=" + replay.jobId() + " import_job 行数=1 客户行数=1");
    }

    @Test
    @DisplayName("C39 失败整批回滚：一行非法数据导致整批不落库")
    void asyncImportFailureRollsBackWholeBatch() throws IOException {
        byte[] template = importService.template(ImportEntity.CUSTOMERS);
        String validCode = tag("CUS-ROLLBACK-OK");
        String invalidCode = tag("CUS-ROLLBACK-BAD");
        byte[] file = workbook(template, List.of(
                new String[]{validCode, "合法客户", "VIP", "5", "月结30天", "false"},
                new String[]{invalidCode, "非法客户", "BAD", "5", "月结30天", "false"}));

        ImportJobView accepted = importJobService.submit(ImportEntity.CUSTOMERS, "W9-FAIL-TOKEN-01",
                "customers-bad.xlsx", file);
        jobIds.add(accepted.jobId());
        ImportJobView terminal = awaitTerminal(accepted.jobId());

        assertThat(terminal.status()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(terminal.committed()).isFalse();
        assertThat(terminal.totalRows()).isEqualTo(2);
        assertThat(terminal.invalidRows()).isEqualTo(1);
        assertThat(terminal.errors()).hasSize(1);
        assertThat(terminal.errors().get(0).row()).isEqualTo(3);
        assertThat(terminal.errors().get(0).field()).isEqualTo("priority");
        assertThat(count("SELECT count(*) FROM customer WHERE code LIKE ?", RUN_PREFIX + "%")).isZero();
        evidence("C39 失败回滚: status=" + terminal.status() + " committed=" + terminal.committed()
                + " errors=" + terminal.errors() + " 落库行数=0（含合法行也未落库）");
    }

    @Test
    @DisplayName("C39 取消生效：PENDING/RUNNING 取消后不落库，且终态不可再取消")
    void asyncImportCancelTakesEffect() throws IOException {
        byte[] template = importService.template(ImportEntity.CUSTOMERS);
        List<String[]> rows = new ArrayList<>();
        for (int index = 0; index < 5000; index++) {
            rows.add(new String[]{tag("CUS-CANCEL-" + index), "取消客户" + index, "NORMAL", "7",
                    "月结30天", "false"});
        }
        byte[] file = workbook(template, rows);

        ImportJobView accepted = importJobService.submit(ImportEntity.CUSTOMERS, "W9-CANCEL-TOKEN-1",
                "customers-big.xlsx", file);
        jobIds.add(accepted.jobId());
        ImportJobView cancelled = importJobService.cancel(ImportEntity.CUSTOMERS, accepted.jobId());

        assertThat(cancelled.status()).isEqualTo(ImportJobStatus.CANCELLED);
        assertThat(cancelled.committed()).isFalse();
        assertThat(count("SELECT count(*) FROM customer WHERE code LIKE ?", RUN_PREFIX + "%")).isZero();
        evidence("C39 取消: jobId=" + accepted.jobId() + " status=" + cancelled.status()
                + " committed=" + cancelled.committed() + " 落库行数=0");

        assertThatThrownBy(() -> importJobService.cancel(ImportEntity.CUSTOMERS, accepted.jobId()))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
        evidence("C39 终态再次取消: 40901 已生效");
    }

    private ImportJobView awaitTerminal(UUID jobId) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        ImportJobView view = null;
        while (System.nanoTime() < deadline) {
            view = importJobService.find(ImportEntity.CUSTOMERS, jobId);
            if (view.status() == ImportJobStatus.SUCCEEDED || view.status() == ImportJobStatus.FAILED
                    || view.status() == ImportJobStatus.CANCELLED) {
                return view;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待导入任务终态被中断", exception);
            }
        }
        throw new IllegalStateException("等待导入任务终态超时: " + jobId + " 最后状态="
                + (view == null ? "null" : view.status()));
    }

    private byte[] workbook(byte[] template, List<String[]> rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int index = 0; index < rows.size(); index++) {
                Row row = sheet.createRow(index + 1);
                String[] values = rows.get(index);
                for (int column = 0; column < values.length; column++) {
                    row.createCell(column).setCellValue(values[column]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private List<String> headers(byte[] file) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            for (int index = 0; index < header.getLastCellNum(); index++) {
                headers.add(header.getCell(index).getStringCellValue());
            }
            return headers;
        }
    }

    private List<List<String>> dataRows(byte[] file) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);
            org.apache.poi.ss.usermodel.DataFormatter formatter = new org.apache.poi.ss.usermodel.DataFormatter();
            List<List<String>> rows = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                for (int column = 0; column < row.getLastCellNum(); column++) {
                    values.add(row.getCell(column) == null ? "" : formatter.formatCellValue(row.getCell(column)));
                }
                rows.add(values);
            }
            return rows;
        }
    }
}
