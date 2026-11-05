package com.hengce.aps.service.mrp;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hengce.aps.infrastructure.persistence.bom.Bom;
import com.hengce.aps.infrastructure.persistence.bom.BomItem;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.mrp.Inventory;
import com.hengce.aps.infrastructure.persistence.mrp.MrpMasterDataRepository;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryMapper;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryTransit;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryTransitMapper;
import com.hengce.aps.infrastructure.persistence.order.Order;
import com.hengce.aps.infrastructure.persistence.order.OrderMapper;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MRP 分析服务单元测试：多级 BOM 与损耗、虚拟件、库存/在途抵扣、齐套分级与阻塞判定。
 *
 * <p>使用 Mockito 模拟只读 Mapper；LambdaQueryWrapper 的列名解析依赖 TableInfo 缓存，
 * 在初始化阶段手工注册相关实体。</p>
 */
class MrpAnalysisServiceTest {

    private final List<Bom> boms = new ArrayList<>();

    private final List<BomItem> bomItems = new ArrayList<>();

    private final List<Order> orders = new ArrayList<>();

    private final List<Inventory> inventories = new ArrayList<>();

    private final List<InventoryTransit> transits = new ArrayList<>();

    private final Map<UUID, Material> materials = new HashMap<>();

    private OrderMapper orderMapper;

    private MrpMasterDataRepository masterDataRepository;

    private InventoryMapper inventoryMapper;

    private InventoryTransitMapper inventoryTransitMapper;

    private MrpAnalysisService service;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(Serializable.class, ObjectTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        TableInfoHelper.initTableInfo(assistant, Bom.class);
        TableInfoHelper.initTableInfo(assistant, BomItem.class);
        TableInfoHelper.initTableInfo(assistant, Material.class);
        TableInfoHelper.initTableInfo(assistant, Inventory.class);
        TableInfoHelper.initTableInfo(assistant, InventoryTransit.class);
        TableInfoHelper.initTableInfo(assistant, Order.class);
        orderMapper = mock(OrderMapper.class);
        masterDataRepository = mock(MrpMasterDataRepository.class);
        inventoryMapper = mock(InventoryMapper.class);
        inventoryTransitMapper = mock(InventoryTransitMapper.class);
        service = new MrpAnalysisService(orderMapper, masterDataRepository,
                inventoryMapper, inventoryTransitMapper, true);
        stubData();
    }

    @Test
    void expandsMultiLevelBomWithScrapAndNetsInventory() {
        UUID fg = material("FG-01", "成品", 0);
        UUID sub = material("SA-01", "半成品", 0);
        UUID raw = material("RM-01", "原料", 2);
        link(fg, sub, "2", "10", false);
        link(sub, raw, "3", "0", false);
        order("SO-001", fg, "10", LocalDate.of(2026, 11, 10));
        inventory(sub, "0", "0");
        inventory(raw, "50", "5");
        transit(raw, "10");

        MrpAnalysisResponse response = service.analyze(
                new MrpAnalysisCommand(UUID.randomUUID(), List.of("SO-001"), true, false));

        assertEquals(MrpStatus.SHORTAGE, response.status());
        assertEquals(1, response.orderCount());
        assertFalse(response.orders().get(0).kitted());
        assertEquals(2, response.orders().get(0).shortages().size());
        assertEquals(2, response.shortages().size());
        MrpAnalysisResponse.MrpShortage rawShortage = response.shortages().get(0);
        assertEquals("RM-01", rawShortage.materialCode());
        assertEquals(0, new BigDecimal("66").compareTo(rawShortage.requiredQty()));
        assertEquals(0, new BigDecimal("55").compareTo(rawShortage.availableQty()));
        assertEquals(0, new BigDecimal("11").compareTo(rawShortage.shortageQty()));
        assertEquals(OffsetDateTime.of(2026, 11, 10, 0, 0, 0, 0, ZoneOffset.UTC),
                rawShortage.earliestRequiredAt());
        assertEquals(OffsetDateTime.of(2026, 11, 8, 0, 0, 0, 0, ZoneOffset.UTC),
                rawShortage.suggestedPurchaseDate());
        assertEquals(0, new BigDecimal("22").compareTo(
                response.shortages().get(1).shortageQty()));
    }

    @Test
    void phantomMaterialIsExpandedButNotRequired() {
        UUID fg = material("FG-02", "成品", 0);
        UUID phantom = material("PH-01", "虚拟件", 0);
        UUID raw = material("RM-02", "原料", 0);
        link(fg, phantom, "1", "0", true);
        link(phantom, raw, "2", "0", false);
        order("SO-002", fg, "10", LocalDate.of(2026, 11, 12));
        inventory(raw, "0", "0");

        MrpAnalysisResponse response = service.analyze(
                new MrpAnalysisCommand(UUID.randomUUID(), List.of("SO-002"), true, false));

        assertEquals(1, response.shortages().size());
        assertEquals("RM-02", response.shortages().get(0).materialCode());
        assertEquals(0, new BigDecimal("20").compareTo(response.shortages().get(0).requiredQty()));
    }

    @Test
    void fullStatusWhenInventorySufficient() {
        UUID fg = material("FG-03", "成品", 0);
        UUID raw = material("RM-03", "原料", 0);
        link(fg, raw, "1", "0", false);
        order("SO-003", fg, "10", LocalDate.of(2026, 11, 15));
        inventory(raw, "20", "0");

        MrpAnalysisResponse response = service.analyze(
                new MrpAnalysisCommand(UUID.randomUUID(), List.of("SO-003"), false, true));

        assertEquals(MrpStatus.FULL, response.status());
        assertTrue(response.orders().get(0).kitted());
        assertTrue(response.shortages().isEmpty());
        assertFalse(response.blocked());
    }

    @Test
    void partialStatusWhenOnlyEarlierOrderIsCovered() {
        UUID fg = material("FG-04", "成品", 0);
        UUID raw = material("RM-04", "原料", 0);
        link(fg, raw, "1", "0", false);
        order("SO-004", fg, "10", LocalDate.of(2026, 11, 10));
        order("SO-005", fg, "10", LocalDate.of(2026, 11, 20));
        inventory(raw, "10", "0");

        MrpAnalysisResponse response = service.analyze(
                new MrpAnalysisCommand(UUID.randomUUID(), List.of("SO-004", "SO-005"), false, true));

        assertEquals(MrpStatus.PARTIAL, response.status());
        assertTrue(response.orders().get(0).kitted());
        assertFalse(response.orders().get(1).kitted());
        assertEquals(MrpStatus.FULL, response.orders().get(0).kittingStatus());
        assertEquals(MrpStatus.SHORTAGE, response.orders().get(1).kittingStatus());
        assertEquals(0, response.orders().get(0).shortageItemCount());
        assertEquals(1, response.orders().get(0).totalItemCount());
        assertEquals(1, response.orders().get(1).shortageItemCount());
        assertEquals(1, response.orders().get(1).totalItemCount());
        assertEquals(1, response.orders().get(1).shortages().size());
        assertEquals(1, response.shortages().size());
        assertEquals(0, new BigDecimal("10").compareTo(response.shortages().get(0).shortageQty()));
    }

    @Test
    void marksOrderPartialWhenOnlySomeItemsShort() {
        UUID fg = material("FG-06", "成品", 0);
        UUID covered = material("RM-09", "已覆盖料", 0);
        UUID shortMaterial = material("RM-10", "缺料", 0);
        link(fg, covered, "1", "0", false);
        link(fg, shortMaterial, "1", "0", false);
        order("SO-009", fg, "10", LocalDate.of(2026, 11, 10));
        inventory(covered, "100", "0");
        inventory(shortMaterial, "0", "0");

        MrpAnalysisResponse response = service.analyze(
                new MrpAnalysisCommand(UUID.randomUUID(), List.of("SO-009"), false, false));

        MrpAnalysisResponse.MrpOrderResult order = response.orders().get(0);
        assertFalse(order.kitted());
        assertEquals(MrpStatus.PARTIAL, order.kittingStatus());
        assertEquals(1, order.shortageItemCount());
        assertEquals(2, order.totalItemCount());
    }

    @Test
    void reportsSubstitutesSupplierAndExpectedArrivalBeyondDueDate() {
        UUID fg = material("FG-07", "成品", 0);
        UUID raw = material("RM-07", "原料", 0);
        UUID alternate = material("RM-08", "替代料", 0);
        link(fg, raw, "1", "0", false);
        substitute(raw, "RM-08", 1, "1:1");
        order("SO-008", fg, "10", LocalDate.of(2026, 11, 10));
        inventory(raw, "0", "0");
        inventory(alternate, "50", "10");
        transit(raw, "10", LocalDate.of(2026, 11, 20));
        UUID supplierId = UUID.randomUUID();
        supplier(raw, supplierId);
        when(masterDataRepository.findSupplierCodes(any())).thenReturn(Map.of(supplierId, "SUP-D02"));

        MrpAnalysisResponse response = service.analyze(
                new MrpAnalysisCommand(UUID.randomUUID(), List.of("SO-008"), false, false));

        MrpAnalysisResponse.MrpShortage shortage = response.shortages().get(0);
        assertEquals("RM-07", shortage.materialCode());
        assertEquals("SUP-D02", shortage.supplierCode());
        assertEquals(1, shortage.substitutes().size());
        MrpAnalysisResponse.MrpSubstitute substitute = shortage.substitutes().get(0);
        assertEquals("RM-08", substitute.materialCode());
        assertEquals(1, substitute.priority());
        assertEquals("1:1", substitute.ratio());
        assertEquals(0, new BigDecimal("40").compareTo(substitute.availableQty()));
        // 在途 +20 晚于交期 +10：给出日期但不冲抵缺口（transitQty=0）。
        assertEquals(0, BigDecimal.ZERO.compareTo(shortage.transitQty()));
        assertEquals(OffsetDateTime.of(2026, 11, 20, 0, 0, 0, 0, ZoneOffset.UTC),
                shortage.expectedArrival());
        assertEquals(MrpStatus.SHORTAGE, response.orders().get(0).kittingStatus());
        assertEquals(1, response.orders().get(0).shortageItemCount());
        assertEquals(1, response.orders().get(0).totalItemCount());
    }

    @Test
    void blockingCheckerBlocksShortageOrder() {
        UUID fg = material("FG-05", "成品", 0);
        UUID raw = material("RM-05", "原料", 0);
        link(fg, raw, "1", "0", false);
        order("SO-006", fg, "10", LocalDate.of(2026, 11, 10));

        MrpBlockingChecker checker = new MrpBlockingChecker(service, true);
        MrpBlockingDecision decision = checker.evaluate(UUID.randomUUID(), List.of("SO-006"), true);

        assertTrue(decision.blocked());
        assertEquals(MrpStatus.SHORTAGE, decision.status());
        assertEquals(List.of("RM-05"), decision.shortageMaterialCodes());
    }

    @Test
    void inventorySwitchOffTreatsAvailableAsZero() {
        UUID fg = material("FG-06", "成品", 0);
        UUID raw = material("RM-06", "原料", 0);
        link(fg, raw, "1", "0", false);
        order("SO-007", fg, "10", LocalDate.of(2026, 11, 10));
        inventory(raw, "100", "0");

        MrpAnalysisService disabled = new MrpAnalysisService(orderMapper, masterDataRepository,
                inventoryMapper, inventoryTransitMapper, false);
        MrpAnalysisResponse response = disabled.analyze(
                new MrpAnalysisCommand(UUID.randomUUID(), List.of("SO-007"), false, false));

        assertEquals(MrpStatus.SHORTAGE, response.status());
        assertFalse(response.inventoryEnabled());
        assertEquals(0, new BigDecimal("10").compareTo(response.shortages().get(0).shortageQty()));
    }

    private void stubData() {
        when(orderMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(orders));
        when(masterDataRepository.findMaterial(any())).thenAnswer(invocation ->
                materials.get(invocation.getArgument(0)));
        when(masterDataRepository.findMaterials(any())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            Map<UUID, Material> found = new HashMap<>();
            for (Object id : ids) {
                Material material = materials.get(id);
                if (material != null) {
                    found.put((UUID) id, material);
                }
            }
            return found;
        });
        when(masterDataRepository.findActiveBoms(any())).thenAnswer(invocation -> {
            UUID parentId = invocation.getArgument(0);
            return boms.stream().filter(bom -> bom.getParentMaterialId().equals(parentId)).toList();
        });
        when(masterDataRepository.findMaterialsByCodes(any())).thenAnswer(invocation -> {
            Collection<String> codes = invocation.getArgument(0);
            Map<String, Material> found = new HashMap<>();
            for (Material material : materials.values()) {
                if (codes.contains(material.getCode())) {
                    found.put(material.getCode(), material);
                }
            }
            return found;
        });
        when(masterDataRepository.findItems(any())).thenAnswer(invocation -> {
            UUID bomId = invocation.getArgument(0);
            return bomItems.stream().filter(item -> item.getBomId().equals(bomId)).toList();
        });
        when(inventoryMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(inventories));
        when(inventoryTransitMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(transits));
    }

    private UUID material(String code, String name, int leadTimeDays) {
        Material material = new Material();
        UUID id = UUID.randomUUID();
        material.setId(id);
        material.setCode(code);
        material.setName(name);
        material.setLeadTimeDays(leadTimeDays);
        materials.put(id, material);
        return id;
    }

    private void link(UUID parentId, UUID childId, String qtyPerUnit, String scrapRate, boolean phantom) {
        UUID bomId;
        List<Bom> existing = boms.stream().filter(bom -> bom.getParentMaterialId().equals(parentId)).toList();
        if (existing.isEmpty()) {
            Bom bom = new Bom();
            bomId = UUID.randomUUID();
            bom.setId(bomId);
            bom.setParentMaterialId(parentId);
            bom.setStatus("ACTIVE");
            bom.setEffectiveDate(LocalDate.of(2026, 1, 1));
            boms.add(bom);
        } else {
            bomId = (UUID) existing.get(0).getId();
        }
        BomItem item = new BomItem();
        item.setId(UUID.randomUUID());
        item.setBomId(bomId);
        item.setChildMaterialId(childId);
        item.setQtyPerUnit(new BigDecimal(qtyPerUnit));
        item.setScrapRate(new BigDecimal(scrapRate));
        item.setIsPhantom(phantom);
        item.setSeqNo(bomItems.size() + 1);
        bomItems.add(item);
    }

    private void order(String orderNo, UUID productId, String qty, LocalDate dueDate) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNo(orderNo);
        order.setProductId(productId);
        order.setQty(new BigDecimal(qty));
        order.setDueDate(dueDate);
        order.setPriority("NORMAL");
        order.setStatus("CONFIRMED");
        orders.add(order);
    }

    private void inventory(UUID materialId, String onHandQty, String safetyStock) {
        Inventory inventory = new Inventory();
        inventory.setId(UUID.randomUUID());
        inventory.setMaterialId(materialId);
        inventory.setWarehouseCode("DEFAULT");
        inventory.setOnHandQty(new BigDecimal(onHandQty));
        inventory.setSafetyStock(new BigDecimal(safetyStock));
        inventory.setSnapshotAt(OffsetDateTime.now(ZoneOffset.UTC));
        inventories.add(inventory);
    }

    private void transit(UUID materialId, String qty) {
        transit(materialId, qty, LocalDate.of(2026, 11, 9));
    }

    private void transit(UUID materialId, String qty, LocalDate expectedDate) {
        InventoryTransit record = new InventoryTransit();
        record.setId(UUID.randomUUID());
        record.setMaterialId(materialId);
        record.setQty(new BigDecimal(qty));
        record.setExpectedDate(expectedDate);
        record.setSourceType("PURCHASE");
        record.setStatus("CONFIRMED");
        transits.add(record);
    }

    private void substitute(UUID childId, String materialCode, int priority, String ratio) {
        BomItem item = bomItems.stream()
                .filter(candidate -> candidate.getChildMaterialId().equals(childId))
                .findFirst().orElseThrow();
        Map<String, Object> substitute = new HashMap<>();
        substitute.put("materialCode", materialCode);
        substitute.put("priority", priority);
        substitute.put("ratio", ratio);
        item.setSubstitutes(List.of(substitute));
    }

    private void supplier(UUID materialId, UUID supplierId) {
        materials.get(materialId).setDefaultSupplierId(supplierId);
    }
}
