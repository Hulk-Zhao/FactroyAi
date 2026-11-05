package com.hengce.aps.service.importexport.handler;

import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.importexport.EntityImportHandler;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportRowException;
import com.hengce.aps.service.importexport.ImportValues;
import com.hengce.aps.service.process.template.OperationTemplateCommand;
import com.hengce.aps.service.process.template.OperationTemplateService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 工序模版导入器：主资源按编码解析；{@code (code, template_version)} 已存在视为跳过。
 */
@Component
public class OperationTemplateImportHandler implements EntityImportHandler {

    private final OperationTemplateService operationTemplateService;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造工序模版导入器。
     *
     * @param operationTemplateService 工序模版服务
     * @param jdbcTemplate JDBC 模板
     */
    public OperationTemplateImportHandler(OperationTemplateService operationTemplateService,
                                          JdbcTemplate jdbcTemplate) {
        this.operationTemplateService = operationTemplateService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.OPERATION_TEMPLATES;
    }

    @Override
    public Map<String, Object> validate(Map<String, String> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("code", ImportValues.requiredText(row, "code", "工序编码", 64));
        normalized.put("name", ImportValues.requiredText(row, "name", "工序名称", 256));
        normalized.put("primaryResourceCode", ImportValues.optionalText(row, "primaryResourceCode", 64));
        normalized.put("standardSeconds", ImportValues.integer(ImportValues.text(row, "standardSeconds"),
                "standardSeconds", "标准工时", 0));
        normalized.put("setupSeconds", ImportValues.integer(ImportValues.text(row, "setupSeconds"),
                "setupSeconds", "换型工时", 0));
        normalized.put("teardownSeconds", ImportValues.integer(ImportValues.text(row, "teardownSeconds"),
                "teardownSeconds", "收尾工时", 0));
        normalized.put("templateVersion", ImportValues.integer(ImportValues.text(row, "templateVersion"),
                "templateVersion", "模版版本", 1));
        return normalized;
    }

    @Override
    public WriteOutcome write(Map<String, Object> normalized) {
        String code = (String) normalized.get("code");
        Integer templateVersion = (Integer) normalized.get("templateVersion");
        int version = templateVersion == null ? 1 : templateVersion;
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_template WHERE tenant_id = ? AND code = ? "
                        + "AND template_version = ? AND deleted = false",
                Integer.class, tenantId(), code, version);
        if (existing != null && existing > 0) {
            return WriteOutcome.SKIPPED;
        }
        UUID resourceId = resolveResource((String) normalized.get("primaryResourceCode"));
        try {
            operationTemplateService.create(new OperationTemplateCommand(code, (String) normalized.get("name"),
                    resourceId, null, List.of(), List.of(), null,
                    (Integer) normalized.get("standardSeconds"),
                    (Integer) normalized.get("setupSeconds"),
                    (Integer) normalized.get("teardownSeconds"), version, null));
            return WriteOutcome.INSERTED;
        } catch (BizException exception) {
            throw new ImportRowException("code", exception.getErrorCode().getCode(), exception.getMessage());
        }
    }

    private UUID resolveResource(String resourceCode) {
        if (resourceCode == null) {
            return null;
        }
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM resource WHERE tenant_id = ? AND code = ? AND deleted = false",
                (resultSet, rowNum) -> resultSet.getObject(1, UUID.class), tenantId(), resourceCode);
        if (ids.isEmpty()) {
            throw new ImportRowException("primaryResourceCode", 40400, "资源不存在: " + resourceCode);
        }
        return ids.get(0);
    }

    private UUID tenantId() {
        UUID tenantId = TenantContext.getTenantId();
        return tenantId == null ? UUID.fromString(ApsConstants.DEFAULT_TENANT_ID) : tenantId;
    }
}
