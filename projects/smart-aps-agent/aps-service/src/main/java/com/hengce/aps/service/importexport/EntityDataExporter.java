package com.hengce.aps.service.importexport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.process.routing.RoutingNode;
import com.hengce.aps.service.masterdata.bom.BomItemView;
import com.hengce.aps.service.masterdata.bom.BomService;
import com.hengce.aps.service.masterdata.bom.BomView;
import com.hengce.aps.service.masterdata.calendar.CalendarService;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarResponse;
import com.hengce.aps.service.masterdata.material.MaterialService;
import com.hengce.aps.service.masterdata.material.MaterialView;
import com.hengce.aps.service.masterdata.order.OrderService;
import com.hengce.aps.service.masterdata.order.OrderView;
import com.hengce.aps.service.masterdata.resource.ResourceService;
import com.hengce.aps.service.masterdata.resource.ResourceView;
import com.hengce.aps.service.process.routing.RoutingEdgeData;
import com.hengce.aps.service.process.routing.RoutingGraphData;
import com.hengce.aps.service.process.routing.RoutingService;
import com.hengce.aps.service.process.template.OperationTemplateService;
import com.hengce.aps.service.strategy.StrategyService;
import com.hengce.aps.service.strategy.StrategyView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 数据导出服务：按实体走各自的查询服务（分页、排序、筛选语义都在服务层），
 * 列顺序与导入模板一致，便于导出 → 修改 → 回填导入。
 */
@Service
public class EntityDataExporter {

    private static final int PAGE_SIZE = 100;

    private static final int MAX_EXPORT_ROWS = ImportLimits.MAX_ROWS;

    private final MaterialService materialService;

    private final ResourceService resourceService;

    private final OrderService orderService;

    private final StrategyService strategyService;

    private final BomService bomService;

    private final CalendarService calendarService;

    private final RoutingService routingService;

    private final OperationTemplateService operationTemplateService;

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    public EntityDataExporter(MaterialService materialService, ResourceService resourceService,
                              OrderService orderService, StrategyService strategyService,
                              BomService bomService, CalendarService calendarService,
                              RoutingService routingService, OperationTemplateService operationTemplateService,
                              JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.materialService = materialService;
        this.resourceService = resourceService;
        this.orderService = orderService;
        this.strategyService = strategyService;
        this.bomService = bomService;
        this.calendarService = calendarService;
        this.routingService = routingService;
        this.operationTemplateService = operationTemplateService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 导出实体数据为 xlsx 字节。
     *
     * @param entity 实体
     * @return xlsx 字节
     */
    public byte[] export(ImportEntity entity) {
        return ExcelDataWriter.write(entity, rows(entity), extraColumns(entity));
    }

    /**
     * 导出时追加在实体列之后的只读展示列（导入时按未知列忽略，保证导出文件可回灌）。
     *
     * @param entity 实体
     * @return 追加列；无则空列表
     */
    private List<ImportColumn> extraColumns(ImportEntity entity) {
        if (entity != ImportEntity.ORDERS) {
            return List.of();
        }
        return List.of(ImportColumn.text("customerName", "客户名称", false, "衡策精工",
                "只读展示列；按 customer_id 关联，导入时忽略"));
    }

    private List<Map<String, Object>> rows(ImportEntity entity) {
        return switch (entity) {
            case MATERIALS -> collect(query -> materialService.list(query, null, null, null, null)).stream()
                    .map(this::materialRow).toList();
            case RESOURCES -> collect(query -> resourceService.list(query, null, null, null, null)).stream()
                    .map(this::resourceRow).toList();
            case ORDERS -> orderRows();
            case STRATEGIES -> collect(query -> strategyService.list(query, null, null, null)).stream()
                    .map(this::strategyRow).toList();
            case BOMS -> bomRows();
            case CALENDARS -> collect(query -> calendarService.list(null, null, null, null, query)).stream()
                    .map(this::calendarRow).toList();
            case ROUTINGS -> routingRows(collect(query -> routingService.list(query, null, null, null)));
            case OPERATION_TEMPLATES -> collect(query -> operationTemplateService.list(query, null, null, null))
                    .stream().map(this::operationTemplateRow).toList();
            case SUPPLIERS -> supplierRows();
            case CUSTOMERS -> customerRows();
            case SETUP_MATRICES -> setupMatrixRows();
        };
    }

    private List<Map<String, Object>> setupMatrixRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbcTemplate.queryForList("select m.key_type, m.from_key, m.to_key, m.scope_type, "
                        + "m.setup_seconds, m.same_series_setup_seconds, m.cross_series_setup_seconds, "
                        + "m.remark, r.code as resource_code, g.code as resource_group_code "
                        + "from setup_matrix_entry m "
                        + "left join resource r on r.id = m.resource_id "
                        + "left join resource_group g on g.id = m.resource_group_id "
                        + "where m.tenant_id = ? and m.deleted = false "
                        + "order by m.key_type, m.scope_type, m.from_key nulls first, m.to_key nulls first",
                        TenantContext.getTenantId())
                .forEach(record -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String keyType = (String) record.get("key_type");
                    row.put("keyType", "MATERIAL_CODE".equals(keyType) ? "PRODUCT" : "PROGRAM");
                    row.put("fromKey", record.get("from_key"));
                    row.put("toKey", record.get("to_key"));
                    row.put("scopeType", record.get("scope_type"));
                    row.put("resourceCode", record.get("resource_code"));
                    row.put("resourceGroupCode", record.get("resource_group_code"));
                    row.put("setupSeconds", record.get("setup_seconds"));
                    row.put("sameSeriesSeconds", record.get("same_series_setup_seconds"));
                    row.put("crossSeriesSeconds", record.get("cross_series_setup_seconds"));
                    row.put("remark", record.get("remark"));
                    rows.add(row);
                });
        return rows;
    }

    private List<Map<String, Object>> customerRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbcTemplate.queryForList("select code, name, priority, lead_time_days, payment_terms, blacklisted "
                        + "from customer where tenant_id = ? and deleted = false order by code",
                        TenantContext.getTenantId())
                .forEach(record -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("code", record.get("code"));
                    row.put("name", record.get("name"));
                    row.put("priority", record.get("priority"));
                    row.put("leadTimeDays", record.get("lead_time_days"));
                    row.put("paymentTerms", record.get("payment_terms"));
                    row.put("blacklisted", record.get("blacklisted"));
                    rows.add(row);
                });
        return rows;
    }

    private List<Map<String, Object>> bomRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BomView bom : collect(query -> bomService.list(query, null, null))) {
            for (BomItemView item : bom.items()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("parentMaterialCode", bom.parentMaterialCode());
                row.put("bomVersion", bom.bomVersion());
                row.put("effectiveDate", bom.effectiveDate());
                row.put("expiryDate", bom.expiryDate());
                row.put("isPanel", bom.isPanel());
                row.put("panelSize", bom.panelSize());
                row.put("componentMaterialCode", item.materialCode());
                row.put("componentQty", item.qtyPerUnit());
                row.put("scrapRate", item.scrapRate());
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, Object>> supplierRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbcTemplate.queryForList("select code, name, lead_time_days, moq, quality_grade, blacklisted "
                        + "from supplier where tenant_id = ? and deleted = false order by code",
                        TenantContext.getTenantId())
                .forEach(record -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("code", record.get("code"));
                    row.put("name", record.get("name"));
                    row.put("leadTimeDays", record.get("lead_time_days"));
                    row.put("moq", record.get("moq"));
                    row.put("qualityGrade", record.get("quality_grade"));
                    row.put("blacklisted", record.get("blacklisted"));
                    rows.add(row);
                });
        return rows;
    }

    private Map<String, Object> materialRow(MaterialView view) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", view.code());
        row.put("name", view.name());
        row.put("type", view.type() == null ? null : view.type().name());
        row.put("spec", view.spec());
        row.put("unit", view.unit());
        row.put("safetyStock", view.safetyStock());
        row.put("moq", view.moq());
        row.put("leadTimeDays", view.leadTimeDays());
        return row;
    }

    private Map<String, Object> resourceRow(ResourceView view) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", view.code());
        row.put("name", view.name());
        row.put("type", view.type() == null ? null : view.type().name());
        row.put("capacityMode", view.capacityMode() == null ? null : view.capacityMode().name());
        row.put("capacityValue", view.capacityValue());
        row.put("batchCapacity", view.batchCapacity());
        row.put("efficiency", view.efficiency());
        row.put("setupSeconds", view.setupSeconds());
        row.put("teardownSeconds", view.teardownSeconds());
        return row;
    }

    private Map<String, Object> orderRow(OrderView view, Map<UUID, CustomerRef> customers) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderNo", view.orderNo());
        row.put("source", view.source() == null ? null : view.source().name());
        row.put("materialCode", view.materialCode());
        row.put("qty", view.qty());
        row.put("dueDate", view.dueDate());
        row.put("priority", view.priority() == null ? null : view.priority().name());
        row.put("remark", view.remark());
        CustomerRef customer = view.customerId() == null ? null : customers.get(view.customerId());
        row.put("customerCode", customer == null ? null : customer.code());
        row.put("customerName", customer == null ? null : customer.name());
        return row;
    }

    private List<Map<String, Object>> orderRows() {
        Map<UUID, CustomerRef> customers = customerIndex();
        return collect(query -> orderService.list(query, null, null, null, null, null, null)).stream()
                .map(view -> orderRow(view, customers)).toList();
    }

    private Map<UUID, CustomerRef> customerIndex() {
        Map<UUID, CustomerRef> index = new HashMap<>();
        jdbcTemplate.queryForList("select id, code, name from customer "
                        + "where tenant_id = ? and deleted = false",
                        TenantContext.getTenantId())
                .forEach(record -> index.put((UUID) record.get("id"),
                        new CustomerRef((String) record.get("code"), (String) record.get("name"))));
        return index;
    }

    /**
     * 客户编码/名称只读引用。
     *
     * @param code 客户编码
     * @param name 客户名称
     */
    private record CustomerRef(String code, String name) {
    }

    private Map<String, Object> strategyRow(StrategyView view) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", view.name());
        row.put("direction", view.direction());
        row.put("strategyVersion", view.strategyVersion());
        row.put("effectiveFrom", view.effectiveFrom());
        row.put("effectiveTo", view.effectiveTo());
        return row;
    }

    private Map<String, Object> calendarRow(CalendarResponse view) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", view.name());
        row.put("calendarVersion", view.calendarVersion());
        row.put("type", view.type());
        row.put("resourceCode", resourceCodeOf(view.resourceId()));
        row.put("effectiveFrom", view.effectiveFrom());
        row.put("effectiveTo", view.effectiveTo());
        row.put("shiftsJson", toJson(view.shifts()));
        row.put("holidaysJson", toJson(view.holidays()));
        return row;
    }

    private List<Map<String, Object>> routingRows(
            List<com.hengce.aps.infrastructure.persistence.process.routing.Routing> routings) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.hengce.aps.infrastructure.persistence.process.routing.Routing routing : routings) {
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("productCode", routing.getProductCode());
            head.put("routingVersion", routing.getRoutingVersion());
            head.put("effectiveDate", routing.getEffectiveDate());
            head.put("expiryDate", routing.getExpiryDate());
            RoutingGraphData graph;
            try {
                graph = routingService.getGraph((UUID) routing.getId());
            } catch (BizException exception) {
                graph = null;
            }
            if (graph == null || graph.nodes().isEmpty()) {
                rows.add(head);
                continue;
            }
            Map<Integer, Integer> fromByTo = new HashMap<>();
            for (RoutingEdgeData edge : graph.edges()) {
                fromByTo.putIfAbsent(edge.toSeqNo(), edge.fromSeqNo());
            }
            for (RoutingNode node : graph.nodes()) {
                Map<String, Object> row = new LinkedHashMap<>(head);
                row.put("seqNo", node.getSeqNo());
                row.put("fromSeqNo", fromByTo.get(node.getSeqNo()));
                row.put("operationCode", node.getOperationCode());
                Object override = node.getOverrides() == null ? null : node.getOverrides().get("primaryResourceId");
                UUID primaryResourceId = override == null ? null : UUID.fromString(override.toString());
                row.put("primaryResourceCode", primaryResourceId == null ? null : resourceCodeOf(primaryResourceId));
                rows.add(row);
            }
        }
        return rows;
    }

    private String resourceCodeOf(UUID resourceId) {
        if (resourceId == null) {
            return null;
        }
        List<String> codes = jdbcTemplate.query(
                "select code from resource where id = ? and tenant_id = ? and deleted = false",
                (rs, index) -> rs.getString(1), resourceId, TenantContext.getTenantId());
        return codes.isEmpty() ? null : codes.get(0);
    }

    private Map<String, Object> operationTemplateRow(
            com.hengce.aps.infrastructure.persistence.process.OperationTemplate template) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", template.getCode());
        row.put("name", template.getName());
        row.put("primaryResourceCode", resourceCodeOf(template.getPrimaryResourceId()));
        row.put("standardSeconds", template.getStandardSeconds());
        row.put("setupSeconds", template.getSetupSeconds());
        row.put("teardownSeconds", template.getTeardownSeconds());
        row.put("templateVersion", template.getTemplateVersion());
        return row;
    }

    private String toJson(List<Map<String, Object>> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "导出 JSON 字段失败");
        }
    }

    private <T> List<T> collect(Function<PageQuery, PageResult<T>> loader) {
        List<T> all = new ArrayList<>();
        int pageNo = 1;
        while (all.size() < MAX_EXPORT_ROWS) {
            PageQuery query = new PageQuery();
            query.setPage(pageNo);
            query.setSize(PAGE_SIZE);
            PageResult<T> result = loader.apply(query);
            if (result.items().isEmpty()) {
                break;
            }
            all.addAll(result.items());
            if (all.size() >= result.total()) {
                break;
            }
            pageNo++;
        }
        return all.size() > MAX_EXPORT_ROWS ? all.subList(0, MAX_EXPORT_ROWS) : all;
    }
}
