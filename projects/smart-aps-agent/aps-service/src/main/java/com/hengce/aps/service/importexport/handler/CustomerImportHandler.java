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
 * 客户导入器（C38）：按 {@code (tenant_id, code)} 幂等 upsert。
 */
@Component
public class CustomerImportHandler implements EntityImportHandler {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造客户导入器。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public CustomerImportHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.CUSTOMERS;
    }

    @Override
    public Map<String, Object> validate(Map<String, String> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("code", ImportValues.requiredText(row, "code", "客户编码", 64));
        normalized.put("name", ImportValues.requiredText(row, "name", "客户名称", 256));
        normalized.put("priority", ImportValues.enumValue(ImportValues.text(row, "priority"),
                "priority", entity().column("priority").allowedValues(), "NORMAL"));
        normalized.put("leadTimeDays", ImportValues.integer(ImportValues.text(row, "leadTimeDays"),
                "leadTimeDays", "交付提前期", 0));
        normalized.put("paymentTerms", ImportValues.optionalText(row, "paymentTerms", 128));
        normalized.put("blacklisted", ImportValues.bool(ImportValues.text(row, "blacklisted"),
                "blacklisted", false));
        return normalized;
    }

    @Override
    public WriteOutcome write(Map<String, Object> normalized) {
        Boolean inserted = jdbcTemplate.queryForObject(
                """
                INSERT INTO customer (tenant_id, code, name, priority, lead_time_days, payment_terms, blacklisted)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, code) WHERE deleted = false
                DO UPDATE SET name = EXCLUDED.name, priority = EXCLUDED.priority,
                    lead_time_days = EXCLUDED.lead_time_days, payment_terms = EXCLUDED.payment_terms,
                    blacklisted = EXCLUDED.blacklisted, updated_at = now(), version = customer.version + 1
                RETURNING (xmax = 0) AS inserted
                """,
                Boolean.class, tenantId(), normalized.get("code"), normalized.get("name"),
                normalized.get("priority"), normalized.get("leadTimeDays"),
                normalized.get("paymentTerms"), normalized.get("blacklisted"));
        return Boolean.TRUE.equals(inserted) ? WriteOutcome.INSERTED : WriteOutcome.UPDATED;
    }

    private UUID tenantId() {
        UUID tenantId = TenantContext.getTenantId();
        return tenantId == null ? UUID.fromString(ApsConstants.DEFAULT_TENANT_ID) : tenantId;
    }
}
