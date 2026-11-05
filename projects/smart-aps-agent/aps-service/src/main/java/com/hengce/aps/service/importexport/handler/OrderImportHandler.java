package com.hengce.aps.service.importexport.handler;

import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.importexport.EntityImportHandler;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportRowException;
import com.hengce.aps.service.importexport.ImportValues;
import com.hengce.aps.service.masterdata.order.OrderCreateCommand;
import com.hengce.aps.service.masterdata.order.OrderPriority;
import com.hengce.aps.service.masterdata.order.OrderService;
import com.hengce.aps.service.masterdata.order.OrderSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 订单导入器：复用 {@link OrderService} 的创建规则；已存在订单号视为跳过，保证重复导入不新增。
 */
@Component
public class OrderImportHandler implements EntityImportHandler {

    private final OrderService orderService;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造订单导入器。
     *
     * @param orderService 订单服务
     * @param jdbcTemplate JDBC 模板
     */
    public OrderImportHandler(OrderService orderService, JdbcTemplate jdbcTemplate) {
        this.orderService = orderService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.ORDERS;
    }

    @Override
    public Map<String, Object> validate(Map<String, String> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("orderNo", ImportValues.requiredText(row, "orderNo", "订单号", 64));
        normalized.put("source", ImportValues.enumValue(ImportValues.text(row, "source"), "source",
                entity().column("source").allowedValues(), "MANUAL"));
        normalized.put("materialCode", ImportValues.requiredText(row, "materialCode", "产品物料编码", 64));
        BigDecimal qty = ImportValues.decimal(ImportValues.text(row, "qty"), "qty", "数量", BigDecimal.ZERO);
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ImportRowException("qty", 42200, "数量必须大于 0");
        }
        normalized.put("qty", qty);
        LocalDate dueDate = ImportValues.date(ImportValues.text(row, "dueDate"), "dueDate", "交期");
        if (dueDate == null) {
            throw new ImportRowException("dueDate", 42200, "交期不能为空");
        }
        normalized.put("dueDate", dueDate);
        normalized.put("priority", ImportValues.enumValue(ImportValues.text(row, "priority"), "priority",
                entity().column("priority").allowedValues(), "NORMAL"));
        normalized.put("remark", ImportValues.optionalText(row, "remark", 2000));
        String customerCode = ImportValues.optionalText(row, "customerCode", 64);
        if (customerCode != null) {
            List<UUID> customerIds = jdbcTemplate.query(
                    "select id from customer where tenant_id = ? and code = ? and deleted = false",
                    (rs, index) -> (UUID) rs.getObject(1), tenantId(), customerCode);
            if (customerIds.isEmpty()) {
                throw new ImportRowException("customerCode", 42200,
                        "客户编码不存在: " + customerCode + "（列 客户编码）");
            }
            normalized.put("customerId", customerIds.get(0));
        }
        return normalized;
    }

    private UUID tenantId() {
        UUID tenantId = TenantContext.getTenantId();
        return tenantId == null ? UUID.fromString(ApsConstants.DEFAULT_TENANT_ID) : tenantId;
    }

    @Override
    public WriteOutcome write(Map<String, Object> normalized) {
        String orderNo = (String) normalized.get("orderNo");
        try {
            orderService.get(orderNo);
            return WriteOutcome.SKIPPED;
        } catch (BizException exception) {
            if (exception.getErrorCode() != ErrorCode.NOT_FOUND) {
                throw exception;
            }
        }
        try {
            orderService.create(new OrderCreateCommand(orderNo,
                    OrderSource.valueOf((String) normalized.get("source")),
                    (String) normalized.get("materialCode"),
                    (BigDecimal) normalized.get("qty"),
                    (LocalDate) normalized.get("dueDate"),
                    OrderPriority.valueOf((String) normalized.get("priority")),
                    (UUID) normalized.get("customerId"), null, (String) normalized.get("remark"), null));
            return WriteOutcome.INSERTED;
        } catch (BizException exception) {
            throw new ImportRowException("orderNo", exception.getErrorCode().getCode(), exception.getMessage());
        }
    }
}
