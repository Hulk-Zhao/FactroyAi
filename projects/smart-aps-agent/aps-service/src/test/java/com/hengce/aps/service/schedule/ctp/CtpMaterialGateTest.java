package com.hengce.aps.service.schedule.ctp;

import com.hengce.aps.infrastructure.persistence.bom.Bom;
import com.hengce.aps.infrastructure.persistence.bom.BomItem;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.mrp.Inventory;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryTransit;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 物料门测试：多级 BOM、虚拟件/损耗、库存与安全库存、在途截断、替代料、深度上限与成环。
 */
class CtpMaterialGateTest {

    private static final LocalDate BASE = LocalDate.of(2026, 11, 5);

    private static final LocalDate REQUESTED = LocalDate.of(2026, 11, 10);

    private final List<Bom> boms = new ArrayList<>();

    private final List<BomItem> bomItems = new ArrayList<>();

    private final List<Inventory> inventories = new ArrayList<>();

    private final List<InventoryTransit> transits = new ArrayList<>();

    private final Map<UUID, Material> materials = new HashMap<>();

    private CtpMaterialGate gate;

    private CtpDataReader reader;

    @BeforeEach
    void setUp() {
        reader = mock(CtpDataReader.class);
        when(reader.findMaterials(any())).thenAnswer(invocation -> {
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
        when(reader.findMaterialsByCodes(any())).thenAnswer(invocation -> {
            Collection<String> codes = invocation.getArgument(0);
            Map<String, Material> found = new HashMap<>();
            for (Material material : materials.values()) {
                if (codes.contains(material.getCode())) {
                    found.put(material.getCode(), material);
                }
            }
            return found;
        });
        when(reader.findActiveBoms(any())).thenAnswer(invocation -> {
            UUID parentId = invocation.getArgument(0);
            return boms.stream().filter(bom -> bom.getParentMaterialId().equals(parentId)).toList();
        });
        when(reader.findBomItems(any())).thenAnswer(invocation -> {
            UUID bomId = invocation.getArgument(0);
            return bomItems.stream().filter(item -> item.getBomId().equals(bomId)).toList();
        });
        when(reader.findInventory(any())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return inventories.stream().filter(row -> ids.contains(row.getMaterialId())).toList();
        });
        when(reader.findTransit(any())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return transits.stream().filter(row -> ids.contains(row.getMaterialId())).toList();
        });
        when(reader.findIncomingItems(any())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return bomItems.stream()
                    .filter(item -> ids.contains(item.getChildMaterialId()))
                    .filter(item -> boms.stream().anyMatch(bom ->
                            ((UUID) bom.getId()).equals(item.getBomId())))
                    .toList();
        });
        gate = new CtpMaterialGate(reader, true);
    }

    @Test
    void reportsShortageWhenNoStockNoTransitAndNoSubstitute() {
        UUID raw = material("RAW-BMS-001", "焊装原料");
        CtpMaterialGate.Result result = gate.evaluate(materials.get(raw), new BigDecimal("40"),
                REQUESTED, BASE, false);

        assertNull(result.materialReadyAt());
        assertEquals(0, result.bomDepth());
        assertEquals(1, result.shortages().size());
        CtpMaterialGate.ShortageLine line = result.shortages().get(0);
        assertEquals("RAW-BMS-001", line.materialCode());
        assertEquals(0, new BigDecimal("40").compareTo(line.requiredQty()));
        assertEquals(0, BigDecimal.ZERO.compareTo(line.availableQty()));
        assertEquals(0, new BigDecimal("40").compareTo(line.shortageQty()));
        assertNull(line.expectedArrival());
        assertTrue(line.unresolved());
        assertTrue(result.evidence().stream()
                .anyMatch(item -> "MATERIAL_SHORTAGE".equals(item.get("kind"))));
    }

    @Test
    void coversShortageWithRegisteredSubstituteOfTheSameMaterial() {
        UUID raw = material("RAW-BMS-002", "焊装原料2");
        UUID alternate = material("RAW-BMS-003", "焊装替代料");
        incomingSubstitute(raw, alternate, 1, "1:1");
        inventory(alternate, "40", "10");

        CtpMaterialGate.Result result = gate.evaluate(materials.get(raw), new BigDecimal("30"),
                REQUESTED, BASE, true);

        assertEquals(BASE, result.materialReadyAt());
        assertEquals(1, result.shortages().size());
        CtpMaterialGate.ShortageLine line = result.shortages().get(0);
        assertEquals(0, new BigDecimal("30").compareTo(line.substituteCoveredQty()));
        assertEquals(0, BigDecimal.ZERO.compareTo(line.remainingQty()));
        assertFalse(line.unresolved());
        assertFalse(result.substitutes().isEmpty());
        CtpMaterialGate.SubstituteLine applied = result.substitutes().get(0);
        assertEquals("RAW-BMS-003", applied.substituteCode());
        assertEquals(0, new BigDecimal("30").compareTo(applied.availableQty()));
        assertEquals(0, new BigDecimal("30").compareTo(applied.coveredQty()));
        assertTrue(applied.applied());
        assertTrue(result.evidence().stream()
                .anyMatch(item -> "SUBSTITUTE_COVERED".equals(item.get("kind"))));
    }

    @Test
    void strictPolicyIgnoresSubstituteAndKeepsShortage() {
        UUID raw = material("RAW-BMS-002", "焊装原料2");
        UUID alternate = material("RAW-BMS-003", "焊装替代料");
        incomingSubstitute(raw, alternate, 1, "1:1");
        inventory(alternate, "100", "0");

        CtpMaterialGate.Result result = gate.evaluate(materials.get(raw), new BigDecimal("30"),
                REQUESTED, BASE, false);

        assertNull(result.materialReadyAt());
        assertTrue(result.substitutes().isEmpty());
        assertTrue(result.shortages().get(0).unresolved());
    }

    @Test
    void transitAfterRequestedDateDoesNotCoverButSetsRealReadyDate() {
        UUID raw = material("RAW-BMS-002", "焊装原料2");
        transit(raw, "30", LocalDate.of(2027, 1, 4));

        CtpMaterialGate.Result result = gate.evaluate(materials.get(raw), new BigDecimal("30"),
                REQUESTED, BASE, false);

        CtpMaterialGate.ShortageLine line = result.shortages().get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(line.transitQty()));
        assertEquals(0, new BigDecimal("30").compareTo(line.shortageQty()));
        assertEquals(OffsetDateTime.of(2027, 1, 4, 0, 0, 0, 0, ZoneOffset.UTC),
                line.expectedArrival());
        assertEquals(LocalDate.of(2027, 1, 4), result.materialReadyAt());
        assertFalse(line.unresolved());
    }

    @Test
    void expandsMultiLevelBomWithPhantomScrapAndIntermediateNetting() {
        UUID fg = material("FG-01", "成品");
        UUID semi = material("SEMI-01", "半成品");
        UUID phantom = material("PH-01", "虚拟件");
        UUID raw = material("RAW-01", "原料");
        UUID rawFromPhantom = material("RAW-02", "虚拟件原料");
        link(fg, semi, "2", "10", false);
        link(semi, phantom, "1", "0", true);
        link(phantom, rawFromPhantom, "2", "0", false);
        link(semi, raw, "3", "0", false);
        inventory(semi, "10", "0");

        CtpMaterialGate.Result result = gate.evaluate(materials.get(fg), new BigDecimal("10"),
                REQUESTED, BASE, false);

        assertEquals(3, result.bomDepth());
        // 半成品需求 22，库存 10 → 缺口 12；再展开：原材料 12×3=36；虚拟件 12×1 → 原料 12×2=24。
        // 中间件可自制（继续展开）所以不单列为缺料，缺料只落到最末级物料。
        assertEquals(2, result.shortages().size());
        Map<String, CtpMaterialGate.ShortageLine> byCode = new HashMap<>();
        result.shortages().forEach(line -> byCode.put(line.materialCode(), line));
        assertFalse(byCode.containsKey("SEMI-01"));
        assertEquals(0, new BigDecimal("36").compareTo(byCode.get("RAW-01").requiredQty()));
        assertEquals(2, byCode.get("RAW-01").level());
        assertEquals(0, new BigDecimal("24").compareTo(byCode.get("RAW-02").requiredQty()));
        assertEquals(3, byCode.get("RAW-02").level());
        assertNull(result.materialReadyAt());
    }

    @Test
    void stopsAtDepthLimitWithIdentifiableFlag() {
        UUID root = material("CHAIN-00", "链首");
        UUID previous = root;
        for (int index = 1; index <= 12; index++) {
            UUID child = material("CHAIN-" + index, "链" + index);
            link(previous, child, "1", "0", false);
            previous = child;
        }

        CtpMaterialGate.Result result = gate.evaluate(materials.get(root), BigDecimal.ONE,
                REQUESTED, BASE, false);

        assertNull(result.materialReadyAt());
        assertTrue(result.depthLimited());
        assertTrue(result.limitFlags().contains(CtpMaterialGate.FLAG_BOM_DEPTH_LIMIT));
        assertEquals(CtpMaterialGate.MAX_BOM_DEPTH, result.bomDepth());
        assertTrue(result.evidence().stream()
                .anyMatch(item -> "BOM_DEPTH_LIMIT".equals(item.get("kind"))));
    }

    @Test
    void safetyStockIsReserved() {
        UUID raw = material("RAW-03", "原料3");
        inventory(raw, "100", "10");

        CtpMaterialGate.Result result = gate.evaluate(materials.get(raw), new BigDecimal("95"),
                REQUESTED, BASE, false);

        CtpMaterialGate.ShortageLine line = result.shortages().get(0);
        assertEquals(0, new BigDecimal("90").compareTo(line.availableQty()));
        assertEquals(0, new BigDecimal("5").compareTo(line.shortageQty()));
    }

    @Test
    void reportsMissingMasterDataInsteadOfSilentTruncation() {
        UUID parent = material("FG-02", "成品2");
        UUID missing = UUID.randomUUID();
        link(parent, missing, "1", "0", false);

        CtpMaterialGate.Result result = gate.evaluate(materials.get(parent), BigDecimal.TEN,
                REQUESTED, BASE, false);

        assertNull(result.materialReadyAt());
        assertTrue(result.missingMasterData());
        assertTrue(result.limitFlags().contains(CtpMaterialGate.FLAG_MISSING_MASTER_DATA));
        assertNotNull(result.evidence().stream()
                .filter(item -> "MISSING_MASTER_DATA".equals(item.get("kind")))
                .findFirst().orElse(null));
    }

    private UUID material(String code, String name) {
        Material material = new Material();
        UUID id = UUID.randomUUID();
        material.setId(id);
        material.setCode(code);
        material.setName(name);
        materials.put(id, material);
        return id;
    }

    private void link(UUID parentId, UUID childId, String qtyPerUnit, String scrapRate,
                      boolean phantom) {
        UUID bomId;
        List<Bom> existing = boms.stream()
                .filter(bom -> bom.getParentMaterialId().equals(parentId)).toList();
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

    private void incomingSubstitute(UUID childId, UUID substituteId, int priority, String ratio) {
        UUID bomId = UUID.randomUUID();
        Bom bom = new Bom();
        bom.setId(bomId);
        bom.setParentMaterialId(material("FG-DUMMY", "占位成品"));
        bom.setStatus("ACTIVE");
        bom.setEffectiveDate(LocalDate.of(2026, 1, 1));
        boms.add(bom);
        BomItem item = new BomItem();
        item.setId(UUID.randomUUID());
        item.setBomId(bomId);
        item.setChildMaterialId(childId);
        item.setQtyPerUnit(BigDecimal.ONE);
        item.setScrapRate(BigDecimal.ZERO);
        item.setIsPhantom(false);
        item.setSeqNo(1);
        Map<String, Object> substitute = new HashMap<>();
        substitute.put("materialCode", materials.get(substituteId).getCode());
        substitute.put("priority", priority);
        substitute.put("ratio", ratio);
        item.setSubstitutes(List.of(substitute));
        bomItems.add(item);
    }

    private void inventory(UUID materialId, String onHand, String safety) {
        Inventory row = new Inventory();
        row.setId(UUID.randomUUID());
        row.setMaterialId(materialId);
        row.setWarehouseCode("DEFAULT");
        row.setOnHandQty(new BigDecimal(onHand));
        row.setSafetyStock(new BigDecimal(safety));
        row.setSnapshotAt(OffsetDateTime.now(ZoneOffset.UTC));
        inventories.add(row);
    }

    private void transit(UUID materialId, String qty, LocalDate expectedDate) {
        InventoryTransit row = new InventoryTransit();
        row.setId(UUID.randomUUID());
        row.setMaterialId(materialId);
        row.setQty(new BigDecimal(qty));
        row.setExpectedDate(expectedDate);
        row.setSourceType("PURCHASE");
        row.setStatus("CONFIRMED");
        transits.add(row);
    }
}
