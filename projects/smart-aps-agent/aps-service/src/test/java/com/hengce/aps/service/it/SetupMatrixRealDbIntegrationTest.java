package com.hengce.aps.service.it;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportMode;
import com.hengce.aps.service.importexport.ImportResult;
import com.hengce.aps.service.importexport.ImportService;
import com.hengce.aps.service.masterdata.resource.ResourceView;
import com.hengce.aps.service.setup.SetupMatrixCommand;
import com.hengce.aps.service.setup.SetupMatrixService;
import com.hengce.aps.service.setup.SetupMatrixView;
import com.hengce.aps.solver.api.SetupMatrixEntry;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C44 换型矩阵真库验收：CRUD/业务键冲突/乐观锁/求解装载，以及 Excel 预览与执行（合法/非法行）。
 */
class SetupMatrixRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Autowired
    private SetupMatrixService setupMatrixService;

    @Autowired
    private ImportService importService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanupMatrixRows() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return;
        }
        String like = RUN_PREFIX + "%";
        jdbcTemplate.update("DELETE FROM setup_matrix_entry WHERE tenant_id = ? AND ("
                        + "from_key LIKE ? OR to_key LIKE ? OR remark LIKE ? "
                        + "OR resource_id IN (SELECT id FROM resource WHERE code LIKE ?))",
                tenantId, like, like, like, like);
    }

    @Test
    void crudPriorityFieldsAndSolverLoad() {
        ResourceView resource = createResource(tag("RES"), CapacityMode.SECONDS_PER_UNIT, BigDecimal.ONE, null);

        SetupMatrixView defaultCell = setupMatrixService.create(new SetupMatrixCommand(
                "PRODUCT", tag("MAT-A"), tag("MAT-B"), null, null,
                1800, 900, 3600, tag("默认值"), null));
        SetupMatrixView resourceCell = setupMatrixService.create(new SetupMatrixCommand(
                "PROGRAM", tag("OP-A"), tag("OP-B"), resource.id(), null,
                600, null, null, tag("资源特例"), null));

        assertEquals("MATERIAL_CODE", defaultCell.keyType());
        assertEquals("TENANT", defaultCell.scopeType());
        assertEquals("OPERATION_CODE", resourceCell.keyType());
        assertEquals("RESOURCE", resourceCell.scopeType());
        assertEquals(900, defaultCell.sameSeriesSeconds());
        assertEquals(3600, defaultCell.crossSeriesSeconds());

        PageQuery query = new PageQuery();
        PageResult<SetupMatrixView> byResource = setupMatrixService.list(
                query, "PROGRAM", null, resource.id(), null, null, null);
        assertEquals(1, byResource.items().size());
        assertEquals(resourceCell.id(), byResource.items().get(0).id());
        assertEquals(1, byResource.total());

        List<SetupMatrixEntry> solverEntries = setupMatrixService.loadForSolver();
        assertTrue(solverEntries.stream().anyMatch(entry ->
                "MATERIAL_CODE".equals(entry.keyType())
                        && tag("MAT-A").equals(entry.fromKey())
                        && tag("MAT-B").equals(entry.toKey())
                        && entry.setupSeconds() == 1800
                        && entry.sameSeriesSeconds() != null
                        && entry.crossSeriesSeconds() != null));
        assertTrue(solverEntries.stream().anyMatch(entry ->
                "OPERATION_CODE".equals(entry.keyType())
                        && resource.id().equals(entry.resourceId())
                        && entry.setupSeconds() == 600));

        BizException duplicate = assertThrows(BizException.class, () -> setupMatrixService.create(
                new SetupMatrixCommand("PRODUCT", tag("MAT-A"), tag("MAT-B"), null, null,
                        1800, 900, 3600, tag("重复默认值"), null)));
        assertEquals(ErrorCode.STATE_CONFLICT.getCode(), duplicate.getErrorCode().getCode());

        BizException staleVersion = assertThrows(BizException.class, () -> setupMatrixService.update(
                defaultCell.id(), new SetupMatrixCommand("PRODUCT", tag("MAT-A"), tag("MAT-B"),
                        null, null, 1800, 900, 3600, tag("过期版本"), 99)));
        assertEquals(ErrorCode.STATE_CONFLICT.getCode(), staleVersion.getErrorCode().getCode());

        SetupMatrixView updated = setupMatrixService.update(defaultCell.id(),
                new SetupMatrixCommand("PRODUCT", tag("MAT-A"), tag("MAT-B"), null, null,
                        2400, 1200, 4800, tag("更新默认值"), defaultCell.version()));
        assertEquals(2400, updated.setupSeconds());
        assertEquals(1200, updated.sameSeriesSeconds());
        assertEquals(defaultCell.version() + 1, updated.version());

        setupMatrixService.delete(resourceCell.id());
        assertThrows(BizException.class, () -> setupMatrixService.get(resourceCell.id()));
        assertEquals(0, setupMatrixService.list(new PageQuery(), "PROGRAM", "RESOURCE",
                resource.id(), null, null, null).total());

        evidence("C44 CRUD/装载：默认值(同系列900/跨系列3600)与资源特例(600)可维护，业务键重复=40901，"
                + "版本冲突=40901，loadForSolver 返回 " + solverEntries.size() + " 条并保留范围/系列字段");
    }

    @Test
    void excelPreviewAndExecuteValidAndInvalidRows() throws IOException {
        ResourceView resource = createResource(tag("IMP-RES"), CapacityMode.SECONDS_PER_UNIT,
                BigDecimal.ONE, null);

        byte[] template = importService.template(ImportEntity.SETUP_MATRICES);
        List<String> headers = headers(template);
        assertEquals(10, headers.size(), "换型矩阵模板应有 10 列");
        assertTrue(headers.get(0).startsWith("维度"), "第 1 列为维度");
        assertTrue(headers.get(6).startsWith("默认换型秒数"), "第 7 列为默认换型秒数");

        byte[] validFile = workbook(template, List.of(
                new String[]{"PRODUCT", tag("IMP-MAT-A"), tag("IMP-MAT-B"), "", "", "",
                        "1800", "900", "3600", tag("导入默认值")},
                new String[]{"PROGRAM", tag("IMP-OP-A"), tag("IMP-OP-B"), "RESOURCE",
                        resource.code(), "", "600", "", "", tag("导入资源特例")}));
        ImportResult preview = importFile(validFile, ImportMode.PREVIEW);
        assertEquals(2, preview.totalRows());
        assertEquals(2, preview.validRows());
        assertEquals(0, preview.invalidRows());
        assertFalse(preview.committed());
        assertEquals("MATERIAL_CODE", preview.preview().get(0).get("keyType"));

        ImportResult executed = importFile(validFile, ImportMode.EXECUTE);
        assertTrue(executed.committed());
        assertEquals(2, executed.inserted());
        assertEquals(0, executed.invalidRows());
        assertEquals(2, count("SELECT count(*) FROM setup_matrix_entry WHERE deleted = false "
                + "AND (from_key LIKE ? OR to_key LIKE ?)", RUN_PREFIX + "%", RUN_PREFIX + "%"));

        ImportResult replayedRows = importFile(validFile, ImportMode.EXECUTE);
        assertEquals(2, replayedRows.skipped(), "业务键与值均未变化时应全部跳过");
        assertEquals(2, count("SELECT count(*) FROM setup_matrix_entry WHERE deleted = false "
                + "AND (from_key LIKE ? OR to_key LIKE ?)", RUN_PREFIX + "%", RUN_PREFIX + "%"));

        byte[] invalidFile = workbook(template, List.<String[]>of(
                new String[]{"PRODUCT", tag("IMP-MAT-C"), tag("IMP-MAT-D"), "TENANT", "", "",
                        "", "", "", tag("非法缺默认值")}));
        ImportResult invalid = importFile(invalidFile, ImportMode.PREVIEW);
        assertEquals(1, invalid.totalRows());
        assertEquals(0, invalid.validRows());
        assertEquals(1, invalid.invalidRows());
        assertEquals(2, invalid.errors().get(0).row());
        assertEquals("setupSeconds", invalid.errors().get(0).field());
        assertEquals(42200, invalid.errors().get(0).code());
        assertTrue(invalid.errors().get(0).message().contains("默认换型秒数"));

        ImportResult invalidExecute = importFile(invalidFile, ImportMode.EXECUTE);
        assertFalse(invalidExecute.committed(), "存在非法行时执行不得落库");
        assertEquals(2, count("SELECT count(*) FROM setup_matrix_entry WHERE deleted = false "
                + "AND (from_key LIKE ? OR to_key LIKE ?)", RUN_PREFIX + "%", RUN_PREFIX + "%"));

        evidence("C44 Excel 导入：模板10列；合法2行 preview valid=2/execute inserted=2；重跑 skipped=2；"
                + "非法行 row=2 field=setupSeconds code=42200，execute committed=false 且库中仍为2行");
    }

    private ImportResult importFile(byte[] file, ImportMode mode) {
        try (InputStream input = new ByteArrayInputStream(file)) {
            return importService.importFile(ImportEntity.SETUP_MATRICES, mode, null,
                    input, file.length);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
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
}
