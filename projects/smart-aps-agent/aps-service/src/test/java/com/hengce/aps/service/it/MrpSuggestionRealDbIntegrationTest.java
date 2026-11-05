package com.hengce.aps.service.it;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.service.masterdata.bom.BomCreateCommand;
import com.hengce.aps.service.masterdata.bom.BomItemCommand;
import com.hengce.aps.service.mrp.InventoryItemCommand;
import com.hengce.aps.service.mrp.InventoryService;
import com.hengce.aps.service.mrp.MrpAnalysisResponse;
import com.hengce.aps.service.mrp.MrpSuggestionCache;
import com.hengce.aps.service.mrp.MrpSuggestionQuery;
import com.hengce.aps.service.mrp.MrpSuggestionService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采购建议（C34）真库集成测试：真实 BOM/库存/在途/订单下验证缺料与齐套、可用量为负、
 * {@code horizonDays} 与 {@code orderNos} 过滤、materialCode 排序与分页。
 * 仅当 {@code APS_TEST_DB_ENABLED=true} 时执行。
 */
class MrpSuggestionRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MrpSuggestionService mrpSuggestionService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private MrpSuggestionCache suggestionCache;

    private final UUID sandboxId = UUID.randomUUID();

    private String orderShort;

    private String orderFar;

    private String codeShort;

    @BeforeEach
    void prepareSuggestionData() {
        Assumptions.assumeTrue(RealDbEnvironment.enabled(), "未启用真库测试：APS_TEST_DB_ENABLED");
        String fg1 = tag("SUG-FG1");
        String fg2 = tag("SUG-FG2");
        String covered = tag("SUG-RM-COVERED");
        codeShort = tag("SUG-RM-SHORT");
        String rawA = tag("SUG-RM-A");
        String rawB = tag("SUG-RM-B");
        String far = tag("SUG-RM-FAR");
        for (String code : List.of(fg1, fg2, covered, codeShort, rawA, rawB, far)) {
            createMaterial(code);
        }
        jdbc.update("UPDATE material SET lead_time_days = 3 WHERE code = ?", codeShort);

        activate(bomService.create(new BomCreateCommand(fg1, "V1", LocalDate.of(2026, 1, 1), null, false, null,
                List.of(new BomItemCommand(codeShort, new BigDecimal("2"), BigDecimal.ZERO, null, null, false),
                        new BomItemCommand(rawA, BigDecimal.ONE, BigDecimal.ZERO, null, null, false),
                        new BomItemCommand(rawB, BigDecimal.ONE, BigDecimal.ZERO, null, null, false),
                        new BomItemCommand(covered, BigDecimal.ONE, BigDecimal.ZERO, null, null, false)))));
        activate(bomService.create(new BomCreateCommand(fg2, "V1", LocalDate.of(2026, 1, 1), null, false, null,
                List.of(new BomItemCommand(far, BigDecimal.ONE, BigDecimal.ZERO, null, null, false)))));

        orderShort = tag("SUG-SO1");
        orderFar = tag("SUG-SO2");
        insertOrder(orderShort, materialId(fg1), new BigDecimal("10"),
                LocalDate.now(ZoneOffset.UTC).plusDays(5));
        insertOrder(orderFar, materialId(fg2), new BigDecimal("5"),
                LocalDate.now(ZoneOffset.UTC).plusDays(60));

        OffsetDateTime snapshot = OffsetDateTime.now(ZoneOffset.UTC);
        inventoryService.upsert(List.of(
                new InventoryItemCommand(codeShort, "WH1", BigDecimal.ZERO, new BigDecimal("30"), snapshot),
                new InventoryItemCommand(rawA, "WH1", new BigDecimal("2"), BigDecimal.ZERO, snapshot),
                new InventoryItemCommand(rawB, "WH1", BigDecimal.ZERO, BigDecimal.ZERO, snapshot),
                new InventoryItemCommand(covered, "WH1", new BigDecimal("10"), BigDecimal.ZERO, snapshot)));
    }

    @AfterEach
    void cleanupSuggestionRows() {
        if (!RealDbEnvironment.enabled()) {
            return;
        }
        String like = RUN_PREFIX + "%";
        jdbc.update("DELETE FROM inventory_transit WHERE material_id IN "
                + "(SELECT id FROM material WHERE code LIKE ?)", like);
        jdbc.update("DELETE FROM inventory WHERE material_id IN "
                + "(SELECT id FROM material WHERE code LIKE ?)", like);
        jdbc.update("DELETE FROM orders WHERE order_no LIKE ?", like);
    }

    @Test
    void shortageAndNonShortageMaterials() {
        PageResult<MrpAnalysisResponse.MrpSuggestionView> result = mrpSuggestionService.suggest(
                new MrpSuggestionQuery(sandboxId, List.of(orderShort), 30), new PageQuery());

        assertEquals(3, result.total(), "齐套物料不应出现在采购建议中");
        assertEquals(List.of(tag("SUG-RM-A"), tag("SUG-RM-B"), codeShort),
                result.items().stream().map(MrpAnalysisResponse.MrpSuggestionView::materialCode).toList());
        MrpAnalysisResponse.MrpSuggestionView shortage = result.items().stream()
                .filter(item -> item.materialCode().equals(codeShort)).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("20").compareTo(shortage.requiredQty()));
        assertEquals(0, new BigDecimal("-30").compareTo(shortage.availableQty()), "可用量可为负");
        assertEquals(0, new BigDecimal("20").compareTo(shortage.shortageQty()),
                "shortage = required - max(available, 0)");
        assertEquals(0, new BigDecimal("20").compareTo(shortage.suggestedQty()));
        assertEquals(LocalDate.now(ZoneOffset.UTC).plusDays(2),
                shortage.suggestedPurchaseDate().toLocalDate(), "建议下单日 = 最早需求日 - leadTimeDays");
        evidence("采购建议(缺料): total=" + result.total() + " shortage=" + shortage);
    }

    @Test
    void horizonFiltersFarOrderAndOrderNosNarrowScope() {
        PageResult<MrpAnalysisResponse.MrpSuggestionView> narrow = mrpSuggestionService.suggest(
                new MrpSuggestionQuery(sandboxId, List.of(), 30), new PageQuery());
        assertEquals(3, narrow.total(), "60 天后的需求应被默认 30 天窗口排除");
        assertTrue(narrow.items().stream()
                .noneMatch(item -> item.materialCode().equals(tag("SUG-RM-FAR"))));

        PageResult<MrpAnalysisResponse.MrpSuggestionView> wide = mrpSuggestionService.suggest(
                new MrpSuggestionQuery(sandboxId, List.of(), 90), new PageQuery());
        assertEquals(4, wide.total(), "90 天窗口应包含远端订单缺料");
        MrpAnalysisResponse.MrpSuggestionView far = wide.items().stream()
                .filter(item -> item.materialCode().equals(tag("SUG-RM-FAR"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("5").compareTo(far.shortageQty()));
        assertEquals(orderFar, far.affectedOrderNos().get(0));

        PageResult<MrpAnalysisResponse.MrpSuggestionView> onlyFar = mrpSuggestionService.suggest(
                new MrpSuggestionQuery(sandboxId, List.of(orderFar), 90), new PageQuery());
        assertEquals(1, onlyFar.total(), "orderNos 过滤后只剩远端订单的缺料");
        assertEquals(tag("SUG-RM-FAR"), onlyFar.items().get(0).materialCode());
        evidence("采购建议(窗口/订单过滤): narrow=" + narrow.total() + " wide=" + wide.total()
                + " onlyFar=" + onlyFar.total());
    }

    @Test
    void sortsByMaterialCodeAndPages() {
        PageQuery page2 = new PageQuery();
        page2.setPage(2);
        page2.setSize(2);
        PageResult<MrpAnalysisResponse.MrpSuggestionView> result = mrpSuggestionService.suggest(
                new MrpSuggestionQuery(sandboxId, List.of(), 90), page2);
        assertEquals(4, result.total());
        assertEquals(2, result.totalPages());
        assertEquals(List.of(tag("SUG-RM-FAR"), codeShort),
                result.items().stream().map(MrpAnalysisResponse.MrpSuggestionView::materialCode).toList());
        evidence("采购建议(排序/分页): itemCodes="
                + result.items().stream().map(MrpAnalysisResponse.MrpSuggestionView::materialCode).toList());

        PageQuery oversized = new PageQuery();
        oversized.setSize(500);
        PageResult<MrpAnalysisResponse.MrpSuggestionView> clamped = mrpSuggestionService.suggest(
                new MrpSuggestionQuery(sandboxId, List.of(), 90), oversized);
        assertEquals(200, clamped.size(), "size 上限 200");
        assertEquals(4, clamped.items().size());
    }

    @Test
    void inventoryWriteInvalidatesSameParameterCache() {
        MrpSuggestionQuery query = new MrpSuggestionQuery(sandboxId, List.of(orderShort), 30);
        PageResult<MrpAnalysisResponse.MrpSuggestionView> before = mrpSuggestionService.suggest(
                query, new PageQuery());
        assertEquals(3, before.total());
        assertTrue(before.items().stream().anyMatch(item -> item.materialCode().equals(codeShort)));

        // 同一入参不缓存过期等待，写库存后立即生效：onHand 50 - 安全库存 30 = 可用 20，恰好齐套。
        inventoryService.upsert(List.of(new InventoryItemCommand(codeShort, "WH1",
                new BigDecimal("50"), new BigDecimal("30"), OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1))));

        PageResult<MrpAnalysisResponse.MrpSuggestionView> after = mrpSuggestionService.suggest(
                query, new PageQuery());
        assertEquals(2, after.total(), "写库存后同一入参必须立即反映新库存，不能等 TTL 过期");
        assertTrue(after.items().stream().noneMatch(item -> item.materialCode().equals(codeShort)));
        evidence("缓存失效(写库存): before.total=" + before.total() + ", after.total=" + after.total());
    }

    @Test
    void cachedCallIsMuchFasterThanColdCall() {
        MrpSuggestionQuery query = new MrpSuggestionQuery(sandboxId, List.of(orderShort), 30);
        long coldMillis = 0;
        int rounds = 10;
        for (int index = 0; index < rounds; index++) {
            suggestionCache.clear();
            long started = System.nanoTime();
            mrpSuggestionService.suggest(query, new PageQuery());
            coldMillis += (System.nanoTime() - started) / 1_000_000L;
        }
        long warmMillis = 0;
        mrpSuggestionService.suggest(query, new PageQuery());
        for (int index = 0; index < rounds; index++) {
            long started = System.nanoTime();
            mrpSuggestionService.suggest(query, new PageQuery());
            warmMillis += (System.nanoTime() - started) / 1_000_000L;
        }
        evidence("采购建议耗时(单线程同入参): coldAvg=" + (coldMillis / rounds)
                + "ms, cachedAvg=" + (warmMillis / rounds) + "ms");
        assertTrue(warmMillis / rounds <= Math.max(1, coldMillis / rounds),
                "缓存命中耗时应不高于冷调用");
    }

    private void insertOrder(String orderNo, UUID productId, BigDecimal qty, LocalDate dueDate) {
        jdbc.update("INSERT INTO orders (id, tenant_id, order_no, product_id, qty, due_date, "
                        + "priority, status) VALUES (?, ?, ?, ?, ?, ?, 'NORMAL', 'CONFIRMED')",
                UUID.randomUUID(), TENANT_ID, orderNo, productId, qty, dueDate);
    }
}
