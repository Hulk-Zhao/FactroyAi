package com.hengce.aps.service.importexport.handler;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.service.importexport.EntityImportHandler;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportRowException;
import com.hengce.aps.service.importexport.ImportValues;
import com.hengce.aps.service.masterdata.material.MaterialService;
import com.hengce.aps.service.masterdata.resource.ResourceService;
import com.hengce.aps.service.process.routing.RoutingCreateCommand;
import com.hengce.aps.service.process.routing.RoutingEdgeCommand;
import com.hengce.aps.service.process.routing.RoutingNodeCommand;
import com.hengce.aps.service.process.routing.RoutingService;
import com.hengce.aps.service.process.template.OperationTemplateService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 工艺路线 Excel 导入：同一产品 + 版本的多行合并为一条路线。
 *
 * <p>填写 {@code seqNo} 的行生成节点（工序编码、主资源按编码解析为 UUID），
 * 填写 {@code fromSeqNo} 的行生成一条 {@code fromSeqNo → seqNo} 的 FS 依赖边；
 * 产品存在性、版本重复、孤立节点/环路/未知序号等图结构与主资源校验
 * 均由 {@link RoutingService#create} 执行，导入层不重复实现。
 * 完全未填节点的行按空白 DAG 草稿创建。</p>
 */
@Component
public class RoutingImportHandler implements EntityImportHandler {

    private static final int CODE_42200 = 42200;

    private final RoutingService routingService;

    private final MaterialService materialService;

    private final ResourceService resourceService;

    private final OperationTemplateService operationTemplateService;

    public RoutingImportHandler(RoutingService routingService, MaterialService materialService,
                                ResourceService resourceService,
                                OperationTemplateService operationTemplateService) {
        this.routingService = routingService;
        this.materialService = materialService;
        this.resourceService = resourceService;
        this.operationTemplateService = operationTemplateService;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.ROUTINGS;
    }

    @Override
    public Map<String, Object> validate(Map<String, String> row) {
        String productCode = ImportValues.requiredText(row, "productCode", "产品物料编码", 64);
        String version = ImportValues.requiredText(row, "routingVersion", "路线版本", 32);
        LocalDate effectiveDate = requiredDate(row, "effectiveDate", "生效日期");
        LocalDate expiryDate = ImportValues.date(ImportValues.text(row, "expiryDate"),
                "expiryDate", "失效日期");
        if (expiryDate != null && expiryDate.isBefore(effectiveDate)) {
            throw new ImportRowException("expiryDate", CODE_42200, "失效日期不能早于生效日期");
        }
        Integer seqNo = ImportValues.integer(ImportValues.text(row, "seqNo"), "seqNo", "节点序号", 1);
        Integer fromSeqNo = ImportValues.integer(ImportValues.text(row, "fromSeqNo"), "fromSeqNo",
                "前驱节点序号", 1);
        if (fromSeqNo != null && seqNo == null) {
            throw new ImportRowException("fromSeqNo", CODE_42200, "填写前驱节点序号时必须同时填写节点序号");
        }
        if (fromSeqNo != null && fromSeqNo.equals(seqNo)) {
            throw new ImportRowException("fromSeqNo", CODE_42200, "前驱节点序号不能等于自身节点序号");
        }
        String operationCode = ImportValues.text(row, "operationCode");
        if (seqNo != null && (operationCode == null || operationCode.isBlank())) {
            throw new ImportRowException("operationCode", CODE_42200, "填写节点序号时工序编码不能为空");
        }
        String primaryResourceCode = ImportValues.text(row, "primaryResourceCode");
        UUID primaryResourceId = null;
        if (primaryResourceCode != null && !primaryResourceCode.isBlank()) {
            primaryResourceId = resolveResource(primaryResourceCode);
        }
        String operationTemplateCode = ImportValues.text(row, "operationTemplateCode");
        UUID operationTemplateId = null;
        if (operationTemplateCode != null && !operationTemplateCode.isBlank()) {
            operationTemplateId = resolveOperationTemplate(operationTemplateCode);
        }
        Integer standardSeconds = ImportValues.integer(ImportValues.text(row, "standardSeconds"),
                "standardSeconds", "标准工时(秒)", 0);
        requireMaterial(productCode, "productCode");

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("productCode", productCode);
        normalized.put("routingVersion", version);
        normalized.put("effectiveDate", effectiveDate);
        normalized.put("expiryDate", expiryDate);
        normalized.put("seqNo", seqNo);
        normalized.put("fromSeqNo", fromSeqNo);
        normalized.put("operationCode", operationCode);
        normalized.put("operationTemplateCode", operationTemplateCode);
        normalized.put("operationTemplateId", operationTemplateId);
        normalized.put("standardSeconds", standardSeconds);
        normalized.put("primaryResourceCode", primaryResourceCode);
        normalized.put("primaryResourceId", primaryResourceId);
        return normalized;
    }

    @Override
    public List<WriteOutcome> writeRows(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = row.get("productCode") + "\u0000" + row.get("routingVersion");
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<WriteOutcome> outcomes = new ArrayList<>(groups.size());
        for (List<Map<String, Object>> group : groups.values()) {
            Map<String, Object> head = group.get(0);
            String productCode = (String) head.get("productCode");
            String version = (String) head.get("routingVersion");
            if (exists(productCode, version)) {
                outcomes.add(WriteOutcome.SKIPPED);
                continue;
            }
            routingService.create(new RoutingCreateCommand(productCode, version,
                    (LocalDate) head.get("effectiveDate"), (LocalDate) head.get("expiryDate"),
                    buildNodes(group), buildEdges(group), null));
            outcomes.add(WriteOutcome.INSERTED);
        }
        return outcomes;
    }

    @Override
    public WriteOutcome write(Map<String, Object> normalized) {
        return writeRows(List.of(normalized)).stream().findFirst().orElse(WriteOutcome.SKIPPED);
    }

    private List<RoutingNodeCommand> buildNodes(List<Map<String, Object>> group) {
        List<Map<String, Object>> nodeRows = new ArrayList<>();
        for (Map<String, Object> row : group) {
            if (row.get("seqNo") != null) {
                nodeRows.add(row);
            }
        }
        nodeRows.sort(Comparator.comparing(row -> (Integer) row.get("seqNo")));
        Set<Integer> seqNos = new HashSet<>();
        List<RoutingNodeCommand> nodes = new ArrayList<>(nodeRows.size());
        for (Map<String, Object> row : nodeRows) {
            Integer seqNo = (Integer) row.get("seqNo");
            if (!seqNos.add(seqNo)) {
                throw new ImportRowException("seqNo", CODE_42200, "同一路线内节点序号重复: " + seqNo);
            }
            String operationCode = (String) row.get("operationCode");
            nodes.add(new RoutingNodeCommand(seqNo, (UUID) row.get("operationTemplateId"), operationCode,
                    operationCode, (Integer) row.get("standardSeconds"), null, null,
                    (UUID) row.get("primaryResourceId"), null, List.of(), Boolean.FALSE));
        }
        return nodes;
    }

    private List<RoutingEdgeCommand> buildEdges(List<Map<String, Object>> group) {
        List<RoutingEdgeCommand> edges = new ArrayList<>();
        for (Map<String, Object> row : group) {
            Integer fromSeqNo = (Integer) row.get("fromSeqNo");
            if (fromSeqNo != null) {
                edges.add(new RoutingEdgeCommand(fromSeqNo, (Integer) row.get("seqNo"),
                        null, null, null, null));
            }
        }
        return edges;
    }

    private boolean exists(String productCode, String version) {
        PageQuery query = new PageQuery();
        query.setSize(1);
        return routingService.list(query, productCode, version, null).total() > 0;
    }

    private void requireMaterial(String code, String field) {
        try {
            materialService.get(code);
        } catch (BizException exception) {
            throw new ImportRowException(field, CODE_42200, "物料不存在: " + code);
        }
    }

    private UUID resolveResource(String code) {
        try {
            return resourceService.get(code).id();
        } catch (BizException exception) {
            throw new ImportRowException("primaryResourceCode", CODE_42200, "资源不存在: " + code);
        }
    }

    /**
     * 按编码解析工序模版主键；同编码多版本时取默认排序（createdAt DESC）的首条。
     *
     * @param code 工序模版编码
     * @return 模版主键
     * @throws ImportRowException 编码不存在时返回 42200
     */
    private UUID resolveOperationTemplate(String code) {
        PageQuery query = new PageQuery();
        query.setSize(200);
        return operationTemplateService.list(query, code, null, null).items().stream()
                .filter(template -> code.equals(template.getCode()))
                .findFirst()
                .map(template -> (UUID) template.getId())
                .orElseThrow(() -> new ImportRowException("operationTemplateCode", CODE_42200,
                        "工序模版不存在: " + code));
    }

    private LocalDate requiredDate(Map<String, String> row, String key, String label) {
        LocalDate value = ImportValues.date(ImportValues.text(row, key), key, label);
        if (value == null) {
            throw new ImportRowException(key, CODE_42200, label + "不能为空");
        }
        return value;
    }
}
