package com.hengce.aps.service.importexport.handler;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.domain.masterdata.resource.ResourceType;
import com.hengce.aps.service.importexport.EntityImportHandler;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportRowException;
import com.hengce.aps.service.importexport.ImportValues;
import com.hengce.aps.service.masterdata.resource.ResourceCreateCommand;
import com.hengce.aps.service.masterdata.resource.ResourceService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 资源导入器：复用 {@link ResourceService} 的创建规则；已存在编码视为跳过。
 */
@Component
public class ResourceImportHandler implements EntityImportHandler {

    private final ResourceService resourceService;

    /**
     * 构造资源导入器。
     *
     * @param resourceService 资源服务
     */
    public ResourceImportHandler(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.RESOURCES;
    }

    @Override
    public Map<String, Object> validate(Map<String, String> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("code", ImportValues.requiredText(row, "code", "资源编码", 64));
        normalized.put("name", ImportValues.requiredText(row, "name", "资源名称", 256));
        normalized.put("type", ImportValues.enumValue(ImportValues.text(row, "type"), "type",
                entity().column("type").allowedValues(), null));
        normalized.put("capacityMode", ImportValues.enumValue(ImportValues.text(row, "capacityMode"),
                "capacityMode", entity().column("capacityMode").allowedValues(), null));
        normalized.put("capacityValue", ImportValues.decimal(ImportValues.text(row, "capacityValue"),
                "capacityValue", "产能值", BigDecimal.ZERO));
        normalized.put("batchCapacity", ImportValues.integer(ImportValues.text(row, "batchCapacity"),
                "batchCapacity", "批量", 1));
        normalized.put("efficiency", ImportValues.decimal(ImportValues.text(row, "efficiency"), "efficiency",
                "效率系数", new BigDecimal("0.5")));
        normalized.put("setupSeconds", ImportValues.integer(ImportValues.text(row, "setupSeconds"),
                "setupSeconds", "换型工时", 0));
        normalized.put("teardownSeconds", ImportValues.integer(ImportValues.text(row, "teardownSeconds"),
                "teardownSeconds", "收尾工时", 0));
        return normalized;
    }

    @Override
    public WriteOutcome write(Map<String, Object> normalized) {
        String code = (String) normalized.get("code");
        try {
            resourceService.get(code);
            return WriteOutcome.SKIPPED;
        } catch (BizException exception) {
            if (exception.getErrorCode() != ErrorCode.NOT_FOUND) {
                throw exception;
            }
        }
        try {
            resourceService.create(new ResourceCreateCommand(code, (String) normalized.get("name"),
                    ResourceType.valueOf((String) normalized.get("type")), null,
                    CapacityMode.valueOf((String) normalized.get("capacityMode")),
                    (BigDecimal) normalized.get("capacityValue"),
                    (Integer) normalized.get("batchCapacity"),
                    (BigDecimal) normalized.get("efficiency"), null,
                    (Integer) normalized.get("setupSeconds"),
                    (Integer) normalized.get("teardownSeconds"), null));
            return WriteOutcome.INSERTED;
        } catch (BizException exception) {
            throw new ImportRowException("code", exception.getErrorCode().getCode(), exception.getMessage());
        }
    }
}
