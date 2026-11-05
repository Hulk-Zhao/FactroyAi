package com.hengce.aps.service.it;

import com.hengce.aps.service.masterdata.bom.BomCreateCommand;
import com.hengce.aps.service.masterdata.bom.BomItemCommand;
import com.hengce.aps.service.masterdata.bom.BomView;
import com.hengce.aps.service.mrp.InventoryItemCommand;
import com.hengce.aps.service.mrp.InventoryService;
import com.hengce.aps.service.mrp.InventoryUpsertResult;
import com.hengce.aps.service.mrp.MrpAnalysisCommand;
import com.hengce.aps.service.mrp.MrpAnalysisResponse;
import com.hengce.aps.service.mrp.MrpAnalysisService;
import com.hengce.aps.service.mrp.MrpBlockingChecker;
import com.hengce.aps.service.mrp.MrpBlockingDecision;
import com.hengce.aps.service.mrp.MrpStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MRP 真库集成测试：V1 + V2 迁移后，覆盖多级 BOM、损耗、虚拟件、库存/在途抵扣、
 * 库存 upsert 幂等与阻塞判定。仅当 {@code APS_TEST_DB_ENABLED=true} 时执行。
 */
class MrpRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Autowired
    private MrpAnalysisService mrpAnalysisService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID fgId;

    private UUID rawId;

    private String orderNo;

    @BeforeEach
    void prepareMrpData() throws Exception {
        Assumptions.assumeTrue(RealDbEnvironment.enabled(), "未启用真库测试：APS_TEST_DB_ENABLED");
        String fg = tag("MRP-FG");
        String sub = tag("MRP-SA");
        String phantom = tag("MRP-PH");
        String raw = tag("MRP-RM");
        createMaterial(fg);
        createMaterial(sub);
        createMaterial(phantom);
        createMaterial(raw);
        fgId = materialId(fg);
        rawId = materialId(raw);

        BomView fgBom = bomService.create(new BomCreateCommand(fg, "V1", LocalDate.of(2026, 1, 1),
                null, false, null, List.of(
                        new BomItemCommand(sub, new BigDecimal("2"), new BigDecimal("10"), null, null, false),
                        new BomItemCommand(phantom, BigDecimal.ONE, BigDecimal.ZERO, null, null, true))));
        activate(fgBom);
        BomView subBom = bomService.create(new BomCreateCommand(sub, "V1", LocalDate.of(2026, 1, 1),
                null, false, null, List.of(
                        new BomItemCommand(raw, new BigDecimal("3"), BigDecimal.ZERO, null, null, false))));
        activate(subBom);

        orderNo = tag("MRP-SO1");
        jdbcTemplate.update("INSERT INTO orders (id, tenant_id, order_no, product_id, qty, due_date, "
                        + "priority, status) VALUES (?, ?, ?, ?, ?, ?, 'NORMAL', 'CONFIRMED')",
                UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000001"),
                orderNo, fgId, new BigDecimal("10"), LocalDate.of(2026, 11, 10));
        jdbcTemplate.update("INSERT INTO inventory_transit (id, tenant_id, material_id, qty, "
                        + "expected_date, source_type, status) VALUES (?, ?, ?, ?, ?, 'PURCHASE', 'CONFIRMED')",
                UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000001"),
                rawId, new BigDecimal("10"), LocalDate.of(2026, 11, 9));
    }

    @AfterEach
    void cleanupMrpRows() {
        if (!RealDbEnvironment.enabled()) {
            return;
        }
        String like = RUN_PREFIX + "%";
        jdbcTemplate.update("DELETE FROM inventory_transit WHERE material_id IN "
                + "(SELECT id FROM material WHERE code LIKE ?)", like);
        jdbcTemplate.update("DELETE FROM inventory WHERE material_id IN "
                + "(SELECT id FROM material WHERE code LIKE ?)", like);
        jdbcTemplate.update("DELETE FROM orders WHERE order_no LIKE ?", like);
    }

    @Test
    void multiLevelExpansionAndIdempotentInventoryUpsert() {
        OffsetDateTime snapshot = OffsetDateTime.of(2026, 11, 4, 0, 0, 0, 0, ZoneOffset.UTC);
        InventoryUpsertResult first = inventoryService.upsert(List.of(
                new InventoryItemCommand(tag("MRP-RM"), "WH1", new BigDecimal("50"),
                        new BigDecimal("5"), snapshot)));
        assertEquals(new InventoryUpsertResult(1, 0), first);

        InventoryUpsertResult second = inventoryService.upsert(List.of(
                new InventoryItemCommand(tag("MRP-RM"), "WH1", new BigDecimal("60"),
                        new BigDecimal("5"), snapshot.plusHours(1))));
        assertEquals(new InventoryUpsertResult(1, 0), second);
        long rows = count("SELECT count(*) FROM inventory WHERE material_id = ? AND warehouse_code = 'WH1'",
                rawId);
        assertEquals(1L, rows, "重复 PUT /inventory 不应产生重复行");
        evidence("inventory upsert 幂等: rows=" + rows + " result=" + second);

        MrpAnalysisResponse analysis = mrpAnalysisService.analyze(new MrpAnalysisCommand(
                UUID.randomUUID(), List.of(orderNo), true, true));
        assertEquals(MrpStatus.SHORTAGE, analysis.status());
        assertEquals(2, analysis.shortages().size());
        MrpAnalysisResponse.MrpShortage rawShortage = analysis.shortages().stream()
                .filter(item -> item.materialCode().equals(tag("MRP-RM")))
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("66").compareTo(rawShortage.requiredQty()));
        assertEquals(0, new BigDecimal("65").compareTo(rawShortage.availableQty()));
        assertEquals(0, new BigDecimal("1").compareTo(rawShortage.shortageQty()));
        assertFalse(analysis.orders().get(0).kitted());
        assertEquals(2, analysis.orders().get(0).shortages().size());
        evidence("MRP 分析(缺料): " + analysis);

        MrpBlockingChecker checker = new MrpBlockingChecker(mrpAnalysisService, true);
        MrpBlockingDecision decision = checker.evaluate(UUID.randomUUID(), List.of(orderNo), true);
        assertTrue(decision.blocked());
        assertEquals(MrpStatus.SHORTAGE, decision.status());
        evidence("MRP 阻塞判定: " + decision);

        inventoryService.upsert(List.of(
                new InventoryItemCommand(tag("MRP-RM"), "WH1", new BigDecimal("100"),
                        BigDecimal.ZERO, snapshot.plusHours(2)),
                new InventoryItemCommand(tag("MRP-SA"), "WH1", new BigDecimal("30"),
                        BigDecimal.ZERO, snapshot.plusHours(2))));
        MrpAnalysisResponse covered = mrpAnalysisService.analyze(new MrpAnalysisCommand(
                UUID.randomUUID(), List.of(orderNo), false, true));
        assertEquals(MrpStatus.FULL, covered.status());
        assertTrue(covered.orders().get(0).kitted());
        assertEquals(0, covered.orders().get(0).shortages().size());
        assertFalse(covered.blocked());
        evidence("MRP 分析(齐套): " + covered);
    }
}
