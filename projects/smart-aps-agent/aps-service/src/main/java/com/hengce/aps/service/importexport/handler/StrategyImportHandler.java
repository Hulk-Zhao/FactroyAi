package com.hengce.aps.service.importexport.handler;

import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.importexport.EntityImportHandler;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportRowException;
import com.hengce.aps.service.importexport.ImportValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 策略导入器：按 {@code (tenant_id, name, strategy_version)} 幂等 upsert。
 */
@Component
public class StrategyImportHandler implements EntityImportHandler {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造策略导入器。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public StrategyImportHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.STRATEGIES;
    }

    @Override
    public Map<String, Object> validate(Map<String, String> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("name", ImportValues.requiredText(row, "name", "策略名称", 128));
        normalized.put("direction", ImportValues.enumValue(ImportValues.text(row, "direction"), "direction",
                entity().column("direction").allowedValues(), "FORWARD"));
        normalized.put("strategyVersion", ImportValues.integer(ImportValues.text(row, "strategyVersion"),
                "strategyVersion", "策略版本", 1));
        LocalDate effectiveFrom = ImportValues.date(ImportValues.text(row, "effectiveFrom"),
                "effectiveFrom", "生效日期");
        if (effectiveFrom == null) {
            throw new ImportRowException("effectiveFrom", 42200, "生效日期不能为空");
        }
        normalized.put("effectiveFrom", effectiveFrom);
        normalized.put("effectiveTo", ImportValues.date(ImportValues.text(row, "effectiveTo"), "effectiveTo",
                "失效日期"));
        LocalDate effectiveTo = (LocalDate) normalized.get("effectiveTo");
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new ImportRowException("effectiveTo", 42200, "失效日期不能早于生效日期");
        }
        return normalized;
    }

    @Override
    public WriteOutcome write(Map<String, Object> normalized) {
        Boolean inserted = jdbcTemplate.queryForObject(
                """
                INSERT INTO strategy (tenant_id, name, direction, strategy_version, effective_from, effective_to)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, name, strategy_version) WHERE deleted = false
                DO UPDATE SET direction = EXCLUDED.direction, effective_from = EXCLUDED.effective_from,
                    effective_to = EXCLUDED.effective_to, updated_at = now(), version = strategy.version + 1
                RETURNING (xmax = 0) AS inserted
                """,
                Boolean.class, tenantId(), normalized.get("name"), normalized.get("direction"),
                normalized.get("strategyVersion"), normalized.get("effectiveFrom"), normalized.get("effectiveTo"));
        return Boolean.TRUE.equals(inserted) ? WriteOutcome.INSERTED : WriteOutcome.UPDATED;
    }

    private UUID tenantId() {
        UUID tenantId = TenantContext.getTenantId();
        return tenantId == null ? UUID.fromString(ApsConstants.DEFAULT_TENANT_ID) : tenantId;
    }
}
