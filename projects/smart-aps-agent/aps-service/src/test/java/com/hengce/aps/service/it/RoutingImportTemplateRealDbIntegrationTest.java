package com.hengce.aps.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportMode;
import com.hengce.aps.service.importexport.ImportResult;
import com.hengce.aps.service.importexport.ImportService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * P4 回归：routings 导入模板补齐 {@code operation_template_id} 与 {@code standard_seconds}，
 * 导入后直接落库，不再需要逐节点 {@code PUT /routings/{id}/graph} 补字段。
 */
@TestPropertySource(properties = "spring.flyway.enabled=false")
class RoutingImportTemplateRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Autowired
    private ImportService importService;

    private final List<UUID> tenants = new ArrayList<>();

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
    void cleanupTenantRows() {
        for (UUID tenant : tenants) {
            jdbc.update("DELETE FROM routing_node WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM routing_edge WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM routing WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM operation_template WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM resource WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM material WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenant);
        }
        tenants.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("导入含工序模版编码与标准工时的 routings 文件后，routing_node 两列直接非空")
    void routingImportWritesTemplateAndStandardSeconds() throws IOException {
        UUID tenant = newTenant();
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        UUID template = newOperationTemplate(tenant, tag("OP-TPL-"));
        String productCode = jdbc.queryForObject("SELECT code FROM material WHERE id = ?",
                String.class, material);
        String resourceCode = jdbc.queryForObject("SELECT code FROM resource WHERE id = ?",
                String.class, resource);
        String templateCode = jdbc.queryForObject("SELECT code FROM operation_template WHERE id = ?",
                String.class, template);

        byte[] templateFile = importService.template(ImportEntity.ROUTINGS);
        List<String> headers = headers(templateFile);
        assertThat(headers).contains("工序模版编码", "标准工时(秒)");
        byte[] file = workbook(templateFile, List.<String[]>of(new String[]{
                productCode, "V1", "2026-11-01", "", "10", "", "OP-10",
                templateCode, "60", resourceCode}));

        ImportResult result = importService.importFile(ImportEntity.ROUTINGS, ImportMode.EXECUTE, null,
                new ByteArrayInputStream(file), file.length);
        assertThat(result.invalidRows()).isZero();
        assertThat(result.committed()).isTrue();

        Map<String, Object> node = jdbc.queryForMap("""
                SELECT operation_template_id, standard_seconds FROM routing_node WHERE tenant_id = ?
                """, tenant);
        assertThat(node.get("operation_template_id")).isEqualTo(template);
        assertThat(node.get("standard_seconds")).isEqualTo(60);

        Map<String, Object> raw = jdbc.queryForMap("""
                SELECT operation_template_id, standard_seconds, operation_code
                  FROM routing_node WHERE tenant_id = ?
                """, tenant);
        evidence("routings 导入落库原始行: " + raw + "（模板列=" + templateCode + "）");
    }

    @Test
    @DisplayName("工序模版编码不存在时返回 42200 错误行，而不是静默留空")
    void unknownOperationTemplateCodeFailsWith42200() throws IOException {
        UUID tenant = newTenant();
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        String productCode = jdbc.queryForObject("SELECT code FROM material WHERE id = ?",
                String.class, material);
        String resourceCode = jdbc.queryForObject("SELECT code FROM resource WHERE id = ?",
                String.class, resource);
        byte[] templateFile = importService.template(ImportEntity.ROUTINGS);
        byte[] file = workbook(templateFile, List.<String[]>of(new String[]{
                productCode, "V1", "2026-11-01", "", "10", "", "OP-10",
                tag("OP-MISSING-"), "60", resourceCode}));

        ImportResult result = importService.importFile(ImportEntity.ROUTINGS, ImportMode.PREVIEW, null,
                new ByteArrayInputStream(file), file.length);
        assertThat(result.invalidRows()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).field()).isEqualTo("operationTemplateCode");
        assertThat(result.errors().get(0).code()).isEqualTo(42200);
        evidence("未知工序模版: field=" + result.errors().get(0).field()
                + ", code=" + result.errors().get(0).code()
                + ", message=" + result.errors().get(0).message());
    }

    private UUID newTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                tenant, tag("P4-TEN-"), "P4 路由导入测试租户");
        tenants.add(tenant);
        TenantContext.setTenantId(tenant);
        return tenant;
    }

    private UUID newMaterial(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO material (id, tenant_id, code, name, type, unit)"
                        + " VALUES (?, ?, ?, ?, 'FINISHED', 'PCS')",
                id, tenant, tag("P4-MAT-"), "P4 测试物料");
        return id;
    }

    private UUID newResource(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO resource (id, tenant_id, code, name, type, capacity_value)"
                        + " VALUES (?, ?, ?, ?, 'PRIMARY', 100)",
                id, tenant, tag("P4-RES-"), "P4 测试资源");
        return id;
    }

    private UUID newOperationTemplate(UUID tenant, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operation_template (id, tenant_id, code, name)"
                        + " VALUES (?, ?, ?, ?)",
                id, tenant, code, "P4 测试工序模版");
        return id;
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
