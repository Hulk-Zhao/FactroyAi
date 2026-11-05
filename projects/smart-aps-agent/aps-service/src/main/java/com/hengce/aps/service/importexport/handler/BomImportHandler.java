package com.hengce.aps.service.importexport.handler;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.service.importexport.EntityImportHandler;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportRowException;
import com.hengce.aps.service.importexport.ImportValues;
import com.hengce.aps.service.masterdata.bom.BomCreateCommand;
import com.hengce.aps.service.masterdata.bom.BomItemCommand;
import com.hengce.aps.service.masterdata.bom.BomService;
import com.hengce.aps.service.masterdata.bom.BomView;
import com.hengce.aps.service.masterdata.material.MaterialService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * BOM Excel 导入：同一父件 + 版本的多行合并为一个 BOM，落库复用 {@link BomService#create}。
 *
 * <p>层级/循环依赖等业务校验全部由 {@code BomService} 执行，导入层不做第二套规则；
 * 已存在的父件+版本按幂等语义跳过。</p>
 */
@Component
public class BomImportHandler implements EntityImportHandler {

    private static final int PAGE_SIZE = 200;

    private static final int CODE_42200 = 42200;

    private static final BigDecimal MAX_SCRAP_RATE = new BigDecimal("100");

    private final BomService bomService;

    private final MaterialService materialService;

    public BomImportHandler(BomService bomService, MaterialService materialService) {
        this.bomService = bomService;
        this.materialService = materialService;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.BOMS;
    }

    @Override
    public Map<String, Object> validate(Map<String, String> row) {
        String parent = ImportValues.requiredText(row, "parentMaterialCode", "父件物料编码", 64);
        String version = ImportValues.requiredText(row, "bomVersion", "BOM版本", 32);
        LocalDate effectiveDate = requiredDate(row, "effectiveDate", "生效日期");
        LocalDate expiryDate = ImportValues.date(ImportValues.text(row, "expiryDate"),
                "expiryDate", "失效日期");
        if (expiryDate != null && expiryDate.isBefore(effectiveDate)) {
            throw new ImportRowException("expiryDate", CODE_42200, "失效日期不能早于生效日期");
        }
        boolean isPanel = ImportValues.bool(ImportValues.text(row, "isPanel"), "isPanel", false);
        Integer panelSize = ImportValues.integer(ImportValues.text(row, "panelSize"),
                "panelSize", "拼板数量", 1);
        String component = ImportValues.requiredText(row, "componentMaterialCode", "子件物料编码", 64);
        BigDecimal qty = ImportValues.decimal(ImportValues.text(row, "componentQty"),
                "componentQty", "子件数量", BigDecimal.ZERO);
        if (qty == null || qty.signum() <= 0) {
            throw new ImportRowException("componentQty", CODE_42200, "子件数量必须大于 0");
        }
        BigDecimal scrapRate = ImportValues.decimal(ImportValues.text(row, "scrapRate"),
                "scrapRate", "损耗率", BigDecimal.ZERO);
        if (scrapRate != null && scrapRate.compareTo(MAX_SCRAP_RATE) > 0) {
            throw new ImportRowException("scrapRate", CODE_42200, "损耗率必须在 0-100 之间");
        }
        requireMaterial(parent, "parentMaterialCode");
        requireMaterial(component, "componentMaterialCode");

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("parentMaterialCode", parent);
        normalized.put("bomVersion", version);
        normalized.put("effectiveDate", effectiveDate);
        normalized.put("expiryDate", expiryDate);
        normalized.put("isPanel", isPanel);
        normalized.put("panelSize", panelSize);
        normalized.put("componentMaterialCode", component);
        normalized.put("componentQty", qty);
        normalized.put("scrapRate", scrapRate == null ? BigDecimal.ZERO : scrapRate);
        return normalized;
    }

    @Override
    public List<WriteOutcome> writeRows(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = row.get("parentMaterialCode") + "\u0000" + row.get("bomVersion");
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<WriteOutcome> outcomes = new ArrayList<>(groups.size());
        for (List<Map<String, Object>> group : groups.values()) {
            Map<String, Object> head = group.get(0);
            String parent = (String) head.get("parentMaterialCode");
            String version = (String) head.get("bomVersion");
            if (exists(parent, version)) {
                outcomes.add(WriteOutcome.SKIPPED);
                continue;
            }
            List<BomItemCommand> items = new ArrayList<>();
            for (Map<String, Object> row : group) {
                items.add(new BomItemCommand((String) row.get("componentMaterialCode"),
                        (BigDecimal) row.get("componentQty"), (BigDecimal) row.get("scrapRate"),
                        null, List.of(), Boolean.FALSE));
            }
            bomService.create(new BomCreateCommand(parent, version, (LocalDate) head.get("effectiveDate"),
                    (LocalDate) head.get("expiryDate"), (Boolean) head.get("isPanel"),
                    (Integer) head.get("panelSize"), items));
            outcomes.add(WriteOutcome.INSERTED);
        }
        return outcomes;
    }

    @Override
    public WriteOutcome write(Map<String, Object> normalized) {
        return writeRows(List.of(normalized)).stream().findFirst().orElse(WriteOutcome.SKIPPED);
    }

    private boolean exists(String parentMaterialCode, String bomVersion) {
        PageQuery query = new PageQuery();
        query.setSize(PAGE_SIZE);
        for (BomView view : bomService.list(query, parentMaterialCode, null).items()) {
            if (view.bomVersion().equals(bomVersion)) {
                return true;
            }
        }
        return false;
    }

    private void requireMaterial(String code, String field) {
        try {
            materialService.get(code);
        } catch (BizException exception) {
            throw new ImportRowException(field, CODE_42200, "物料不存在: " + code);
        }
    }

    private LocalDate requiredDate(Map<String, String> row, String key, String label) {
        LocalDate value = ImportValues.date(ImportValues.text(row, key), key, label);
        if (value == null) {
            throw new ImportRowException(key, CODE_42200, label + "不能为空");
        }
        return value;
    }
}
