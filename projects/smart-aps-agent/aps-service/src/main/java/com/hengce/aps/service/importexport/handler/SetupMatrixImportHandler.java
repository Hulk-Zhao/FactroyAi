package com.hengce.aps.service.importexport.handler;

import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.importexport.EntityImportHandler;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportRowException;
import com.hengce.aps.service.importexport.ImportValues;
import com.hengce.aps.service.setup.SetupMatrixCommand;
import com.hengce.aps.service.setup.SetupMatrixService;
import com.hengce.aps.service.setup.SetupMatrixView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 换型矩阵 Excel 导入器（C44）。
 *
 * <p>校验列：维度、FROM/TO 键、生效范围、资源/资源组编码、默认换型秒数与同/跨系列特例。
 * 资源/资源组按编码解析；业务键
 * {@code (keyType, fromKey, toKey, scopeType, resourceId, resourceGroupId)} 已存在则更新，
 * 值相同则跳过。</p>
 */
@Component
public class SetupMatrixImportHandler implements EntityImportHandler {

    private static final String SCOPE_TENANT = "TENANT";

    private static final String SCOPE_RESOURCE_GROUP = "RESOURCE_GROUP";

    private static final String SCOPE_RESOURCE = "RESOURCE";

    private static final int CODE_42200 = 42200;

    private static final int CODE_40400 = 40400;

    private final SetupMatrixService setupMatrixService;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造导入器。
     *
     * @param setupMatrixService 换型矩阵服务
     * @param jdbcTemplate JDBC 模板
     */
    public SetupMatrixImportHandler(SetupMatrixService setupMatrixService, JdbcTemplate jdbcTemplate) {
        this.setupMatrixService = setupMatrixService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.SETUP_MATRICES;
    }

    @Override
    public Map<String, Object> validate(Map<String, String> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        String keyType = normalizeKeyType(ImportValues.text(row, "keyType"));
        normalized.put("keyType", keyType);
        String fromKey = ImportValues.optionalText(row, "fromKey", 64);
        String toKey = ImportValues.optionalText(row, "toKey", 64);
        normalized.put("fromKey", fromKey);
        normalized.put("toKey", toKey);

        String resourceCode = ImportValues.optionalText(row, "resourceCode", 64);
        String groupCode = ImportValues.optionalText(row, "resourceGroupCode", 64);
        String scopeType = resolveScope(row, resourceCode, groupCode);
        if (SCOPE_RESOURCE.equals(scopeType) && resourceCode == null) {
            throw new ImportRowException("resourceCode", CODE_42200, "RESOURCE 范围必须填写资源编码");
        }
        if (SCOPE_RESOURCE_GROUP.equals(scopeType) && groupCode == null) {
            throw new ImportRowException("resourceGroupCode", CODE_42200,
                    "RESOURCE_GROUP 范围必须填写资源组编码");
        }
        if (SCOPE_TENANT.equals(scopeType) && (resourceCode != null || groupCode != null)) {
            throw new ImportRowException("scopeType", CODE_42200,
                    "TENANT 范围不能填写资源/资源组编码");
        }
        UUID resourceId = SCOPE_RESOURCE.equals(scopeType)
                ? resolveResource(resourceCode) : null;
        UUID resourceGroupId = SCOPE_RESOURCE_GROUP.equals(scopeType)
                ? resolveResourceGroup(groupCode) : null;
        normalized.put("scopeType", scopeType);
        normalized.put("resourceId", resourceId);
        normalized.put("resourceGroupId", resourceGroupId);

        Integer setupSeconds = ImportValues.integer(ImportValues.text(row, "setupSeconds"),
                "setupSeconds", "默认换型秒数", 0);
        if (setupSeconds == null) {
            throw new ImportRowException("setupSeconds", CODE_42200, "默认换型秒数不能为空");
        }
        normalized.put("setupSeconds", setupSeconds);
        Integer sameSeriesSeconds = ImportValues.integer(ImportValues.text(row, "sameSeriesSeconds"),
                "sameSeriesSeconds", "同系列换型秒数", 0);
        Integer crossSeriesSeconds = ImportValues.integer(ImportValues.text(row, "crossSeriesSeconds"),
                "crossSeriesSeconds", "跨系列换型秒数", 0);
        normalized.put("sameSeriesSeconds", sameSeriesSeconds);
        normalized.put("crossSeriesSeconds", crossSeriesSeconds);
        normalized.put("remark", ImportValues.optionalText(row, "remark", 255));
        return normalized;
    }

    @Override
    public WriteOutcome write(Map<String, Object> normalized) {
        String keyType = (String) normalized.get("keyType");
        String fromKey = (String) normalized.get("fromKey");
        String toKey = (String) normalized.get("toKey");
        UUID resourceId = (UUID) normalized.get("resourceId");
        UUID resourceGroupId = (UUID) normalized.get("resourceGroupId");
        try {
            SetupMatrixView existing = setupMatrixService.findByBusinessKey(
                    keyType, fromKey, toKey, resourceId, resourceGroupId);
            if (existing != null && isUnchanged(existing, normalized)) {
                return WriteOutcome.SKIPPED;
            }
            SetupMatrixCommand command = new SetupMatrixCommand(
                    keyType, fromKey, toKey, resourceId, resourceGroupId,
                    (Integer) normalized.get("setupSeconds"),
                    (Integer) normalized.get("sameSeriesSeconds"),
                    (Integer) normalized.get("crossSeriesSeconds"),
                    (String) normalized.get("remark"),
                    existing == null ? null : existing.version());
            if (existing == null) {
                setupMatrixService.create(command);
                return WriteOutcome.INSERTED;
            }
            setupMatrixService.update(existing.id(), command);
            return WriteOutcome.UPDATED;
        } catch (BizException exception) {
            throw new ImportRowException("keyType", exception.getErrorCode().getCode(),
                    exception.getMessage());
        }
    }

    private static boolean isUnchanged(SetupMatrixView existing, Map<String, Object> normalized) {
        return existing.keyType().equals(normalized.get("keyType"))
                && Objects.equals(existing.fromKey(), normalized.get("fromKey"))
                && Objects.equals(existing.toKey(), normalized.get("toKey"))
                && Objects.equals(existing.scopeType(), normalized.get("scopeType"))
                && Objects.equals(existing.resourceId(), normalized.get("resourceId"))
                && Objects.equals(existing.resourceGroupId(), normalized.get("resourceGroupId"))
                && Objects.equals(existing.setupSeconds(), normalized.get("setupSeconds"))
                && Objects.equals(existing.sameSeriesSeconds(), normalized.get("sameSeriesSeconds"))
                && Objects.equals(existing.crossSeriesSeconds(), normalized.get("crossSeriesSeconds"))
                && Objects.equals(existing.remark(), normalized.get("remark"));
    }

    private static String normalizeKeyType(String value) {
        if (value == null) {
            throw new ImportRowException("keyType", CODE_42200, "维度不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PRODUCT", "MATERIAL_CODE" -> "MATERIAL_CODE";
            case "PROGRAM", "OPERATION_CODE" -> "OPERATION_CODE";
            default -> throw new ImportRowException("keyType", CODE_42200,
                    "维度只允许 PRODUCT/PROGRAM");
        };
    }

    private static String resolveScope(Map<String, String> row, String resourceCode, String groupCode) {
        String requested = ImportValues.text(row, "scopeType");
        if (requested != null) {
            String normalized = requested.trim().toUpperCase(Locale.ROOT);
            if (SCOPE_TENANT.equals(normalized) || SCOPE_RESOURCE_GROUP.equals(normalized)
                    || SCOPE_RESOURCE.equals(normalized)) {
                return normalized;
            }
            throw new ImportRowException("scopeType", CODE_42200,
                    "生效范围只允许 TENANT/RESOURCE_GROUP/RESOURCE");
        }
        if (resourceCode != null && groupCode != null) {
            throw new ImportRowException("scopeType", CODE_42200,
                    "资源编码与资源组编码不能同时填写");
        }
        if (resourceCode != null) {
            return SCOPE_RESOURCE;
        }
        return groupCode != null ? SCOPE_RESOURCE_GROUP : SCOPE_TENANT;
    }

    private UUID resolveResource(String code) {
        return resolveId("resource", code, "resourceCode", "资源不存在: ");
    }

    private UUID resolveResourceGroup(String code) {
        return resolveId("resource_group", code, "resourceGroupCode", "资源组不存在: ");
    }

    private UUID resolveId(String table, String code, String field, String messagePrefix) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM " + table + " WHERE tenant_id = ? AND code = ? AND deleted = false",
                (resultSet, rowNum) -> resultSet.getObject(1, UUID.class), tenantId(), code);
        if (ids.isEmpty()) {
            throw new ImportRowException(field, CODE_40400, messagePrefix + code);
        }
        return ids.get(0);
    }

    private UUID tenantId() {
        UUID tenantId = TenantContext.getTenantId();
        return tenantId == null ? UUID.fromString(ApsConstants.DEFAULT_TENANT_ID) : tenantId;
    }
}
