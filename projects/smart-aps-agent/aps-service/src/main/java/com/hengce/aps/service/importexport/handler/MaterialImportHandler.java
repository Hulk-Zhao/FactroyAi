package com.hengce.aps.service.importexport.handler;

import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.importexport.EntityImportHandler;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 物料导入器：按 {@code (tenant_id, code)} 幂等 upsert，重复导入同一编码只更新不新增。
 */
@Component
public class MaterialImportHandler implements EntityImportHandler {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造物料导入器。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public MaterialImportHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.MATERIALS;
    }

    @Override
    public Map<String, Object> validate(Map<String, String> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("code", ImportValues.requiredText(row, "code", "物料编码", 64));
        normalized.put("name", ImportValues.requiredText(row, "name", "物料名称", 256));
        normalized.put("type", ImportValues.enumValue(ImportValues.text(row, "type"), "type",
                entity().column("type").allowedValues(), null));
        normalized.put("spec", ImportValues.optionalText(row, "spec", 512));
        normalized.put("unit", ImportValues.requiredText(row, "unit", "基本单位", 16));
        normalized.put("safetyStock", ImportValues.decimal(ImportValues.text(row, "safetyStock"),
                "safetyStock", "安全库存", java.math.BigDecimal.ZERO));
        normalized.put("moq", ImportValues.decimal(ImportValues.text(row, "moq"),
                "moq", "最小起订量", java.math.BigDecimal.ZERO));
        normalized.put("leadTimeDays", ImportValues.integer(ImportValues.text(row, "leadTimeDays"),
                "leadTimeDays", "采购提前期", 0));
        return normalized;
    }

    @Override
    public WriteOutcome write(Map<String, Object> normalized) {
        Boolean inserted = jdbcTemplate.queryForObject(
                """
                INSERT INTO material (tenant_id, code, name, type, spec, unit, safety_stock, moq, lead_time_days)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, code) WHERE deleted = false
                DO UPDATE SET name = EXCLUDED.name, type = EXCLUDED.type, spec = EXCLUDED.spec,
                    unit = EXCLUDED.unit, safety_stock = EXCLUDED.safety_stock, moq = EXCLUDED.moq,
                    lead_time_days = EXCLUDED.lead_time_days, updated_at = now(), version = material.version + 1
                RETURNING (xmax = 0) AS inserted
                """,
                Boolean.class,
                tenantId(), normalized.get("code"), normalized.get("name"), normalized.get("type"),
                normalized.get("spec"), normalized.get("unit"), normalized.get("safetyStock"),
                normalized.get("moq"), normalized.get("leadTimeDays"));
        return Boolean.TRUE.equals(inserted) ? WriteOutcome.INSERTED : WriteOutcome.UPDATED;
    }

    private UUID tenantId() {
        UUID tenantId = TenantContext.getTenantId();
        return tenantId == null ? UUID.fromString(ApsConstants.DEFAULT_TENANT_ID) : tenantId;
    }
}
