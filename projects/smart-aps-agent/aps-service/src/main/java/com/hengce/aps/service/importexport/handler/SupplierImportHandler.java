package com.hengce.aps.service.importexport.handler;

import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.importexport.EntityImportHandler;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportValues;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 供应商导入器：按 {@code (tenant_id, code)} 幂等 upsert。
 */
@Component
public class SupplierImportHandler implements EntityImportHandler {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造供应商导入器。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public SupplierImportHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.SUPPLIERS;
    }

    @Override
    public Map<String, Object> validate(Map<String, String> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("code", ImportValues.requiredText(row, "code", "供应商编码", 64));
        normalized.put("name", ImportValues.requiredText(row, "name", "供应商名称", 256));
        normalized.put("leadTimeDays", ImportValues.integer(ImportValues.text(row, "leadTimeDays"),
                "leadTimeDays", "采购提前期", 0));
        normalized.put("moq", ImportValues.decimal(ImportValues.text(row, "moq"), "moq", "最小起订量",
                BigDecimal.ZERO));
        normalized.put("qualityGrade", ImportValues.enumValue(ImportValues.text(row, "qualityGrade"),
                "qualityGrade", entity().column("qualityGrade").allowedValues(), null));
        normalized.put("blacklisted", ImportValues.bool(ImportValues.text(row, "blacklisted"), "blacklisted",
                false));
        return normalized;
    }

    @Override
    public WriteOutcome write(Map<String, Object> normalized) {
        Boolean inserted = jdbcTemplate.queryForObject(
                """
                INSERT INTO supplier (tenant_id, code, name, lead_time_days, moq, quality_grade, blacklisted)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, code) WHERE deleted = false
                DO UPDATE SET name = EXCLUDED.name, lead_time_days = EXCLUDED.lead_time_days,
                    moq = EXCLUDED.moq, quality_grade = EXCLUDED.quality_grade,
                    blacklisted = EXCLUDED.blacklisted, updated_at = now(), version = supplier.version + 1
                RETURNING (xmax = 0) AS inserted
                """,
                Boolean.class, tenantId(), normalized.get("code"), normalized.get("name"),
                normalized.get("leadTimeDays"), normalized.get("moq"), normalized.get("qualityGrade"),
                normalized.get("blacklisted"));
        return Boolean.TRUE.equals(inserted) ? WriteOutcome.INSERTED : WriteOutcome.UPDATED;
    }

    private UUID tenantId() {
        UUID tenantId = TenantContext.getTenantId();
        return tenantId == null ? UUID.fromString(ApsConstants.DEFAULT_TENANT_ID) : tenantId;
    }
}
