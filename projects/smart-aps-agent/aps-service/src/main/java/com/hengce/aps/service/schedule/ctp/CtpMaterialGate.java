package com.hengce.aps.service.schedule.ctp;

import com.hengce.aps.infrastructure.persistence.bom.Bom;
import com.hengce.aps.infrastructure.persistence.bom.BomItem;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.mrp.Inventory;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryTransit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * CTP 物料门（C53 §25.28 物料可齐日）：多级 BOM 展开 → 逐级净需求 → 物料可齐日。
 *
 * <p>口径与 C49 §25.24 对齐：</p>
 * <ul>
 *   <li>可用量 = {@code max(0, 现有库存 + 需求日前的未取消在途 - 安全库存)}；在途仅当
 *       {@code expected_date} 不晚于需求日时计入；{@code expectedArrival} 取最早一条在途日期，
 *       即使晚于需求日也只预告、不冲抵。</li>
 *   <li>替代料来自 {@code bom_item.substitutes}，可用量同口径；按 {@code priority} 升序
 *       （null 置后）、同优先级按编码升序分配。直接询单物料时按“子件”反查 ACTIVE BOM 行。</li>
 *   <li>替代策略 {@code STRICT} 时不使用替代料，缺口直接判不可齐。</li>
 * </ul>
 *
 * <p>层级策略：最多展开 {@value #MAX_BOM_DEPTH} 层。超深、成环、主数据缺失都不会静默截断，
 * 而是置位可识别的 {@code limitFlags} 并让 {@code materialReadyAt} 为 {@code null}。</p>
 *
 * <p>本类只读，不写任何业务表。</p>
 */
@Service
public class CtpMaterialGate {

    /** BOM 最大展开层级。 */
    static final int MAX_BOM_DEPTH = 10;

    /** 证据条数上限（C53）。 */
    static final int MAX_EVIDENCE = 8;

    /** 数量统一保留的小数位。 */
    static final int QTY_SCALE = 6;

    /** 限制标记：BOM 超深。 */
    public static final String FLAG_BOM_DEPTH_LIMIT = "BOM_DEPTH_LIMIT";

    /** 限制标记：BOM 成环。 */
    public static final String FLAG_BOM_CYCLE = "BOM_CYCLE_DETECTED";

    /** 限制标记：主数据缺失。 */
    public static final String FLAG_MISSING_MASTER_DATA = "MISSING_MASTER_DATA";

    /** 限制标记：库存读取关闭。 */
    public static final String FLAG_INVENTORY_DISABLED = "INVENTORY_DISABLED";

    /** 限制标记：存在无预计到货日的在途（到货时点不可判定）。 */
    public static final String FLAG_UNDATED_TRANSIT = "UNDATED_TRANSIT";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final BigDecimal ONE = BigDecimal.ONE;

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static final String TRANSIT_CANCELLED = "CANCELLED";

    private static final Comparator<Integer> PRIORITY_ORDER =
            Comparator.nullsLast(Comparator.naturalOrder());

    private final CtpDataReader dataReader;

    private final boolean inventoryEnabled;

    /**
     * 构造物料门。
     *
     * @param dataReader CTP 只读数据读取器
     * @param inventoryEnabled 库存读取开关，关闭时可用量按 0 计算并置位限制标记
     */
    public CtpMaterialGate(CtpDataReader dataReader,
                           @Value("${aps.mrp.inventory-enabled:true}") boolean inventoryEnabled) {
        this.dataReader = dataReader;
        this.inventoryEnabled = inventoryEnabled;
    }

    /**
     * 执行物料可齐日评估。
     *
     * @param root 询单物料（已确认存在）
     * @param demandQty 需求数量
     * @param requestedDate 客户请求交期
     * @param baseDate 基准日期（业务时区“今天”），可齐日不早于该日
     * @param allowSubstitute 是否允许替代料
     * @return 物料门结果
     */
    public Result evaluate(Material root, BigDecimal demandQty, LocalDate requestedDate,
                           LocalDate baseDate, boolean allowSubstitute) {
        GateState state = new GateState(requestedDate, baseDate, allowSubstitute);
        if (!inventoryEnabled) {
            state.limitFlags.add(FLAG_INVENTORY_DISABLED);
            state.missingData = true;
        }
        UUID rootId = (UUID) root.getId();
        Map<UUID, Demand> current = new LinkedHashMap<>();
        Demand rootDemand = new Demand(rootId, qty(demandQty), 0, Collections.emptyList());
        registerSubstitutes(rootDemand, incomingSubstitutes(Set.of(rootId)));
        current.put(rootId, rootDemand);

        for (int level = 0; level <= MAX_BOM_DEPTH && !current.isEmpty(); level++) {
            LevelView view = loadLevel(current.keySet(), current, state);
            Map<UUID, Demand> next = new LinkedHashMap<>();
            List<Demand> ordered = current.values().stream()
                    .sorted(Comparator.comparing(demand -> codeOf(view.materials().get(demand.materialId),
                            demand.materialId))).toList();
            for (Demand demand : ordered) {
                handleDemand(demand, level, view, state, next);
            }
            current = next;
        }
        if (!current.isEmpty()) {
            state.limitFlags.add(FLAG_BOM_DEPTH_LIMIT);
            state.unresolved = true;
            state.extraEvidence.add(evidence("BOM_DEPTH_LIMIT",
                    "BOM 展开超过 " + MAX_BOM_DEPTH + " 层后仍有未判定需求，已停止展开"));
        }
        return buildResult(state);
    }

    private void handleDemand(Demand demand, int level, LevelView view, GateState state,
                              Map<UUID, Demand> next) {
        Material material = view.materials().get(demand.materialId);
        if (material == null) {
            state.missingData = true;
            state.unresolved = true;
            state.limitFlags.add(FLAG_MISSING_MASTER_DATA);
            state.extraEvidence.add(evidence("MISSING_MASTER_DATA",
                    "物料主数据缺失：主键 " + demand.materialId + "（第 " + level + " 层，需求 "
                            + qty(demand.required) + "），无法判定可齐日"));
            return;
        }
        if (demand.phantom) {
            if (!expand(material, demand, level, state, next, demand.required)) {
                state.missingData = true;
                state.unresolved = true;
                state.limitFlags.add(FLAG_MISSING_MASTER_DATA);
                state.extraEvidence.add(evidence("MISSING_MASTER_DATA",
                        "虚拟件 " + material.getCode() + " 无 ACTIVE BOM，无法继续展开"));
            }
            return;
        }
        Coverage coverage = cover(demand, material, view, state);
        Bom bom = findActiveBom(demand.materialId, state);
        boolean willExpand = bom != null && coverage.remaining.signum() > 0
                && level < MAX_BOM_DEPTH;
        if (coverage.shortage.signum() > 0 && !willExpand) {
            state.shortages.add(new ShortageLine(material.getCode(), material.getName(), level,
                    qty(demand.required), qty(coverage.availableAtRequest), qty(coverage.shortage),
                    qty(coverage.onHand), qty(coverage.transitAtRequest), qty(coverage.safetyStock),
                    coverage.expectedArrival, qty(coverage.substituteCovered),
                    qty(coverage.remaining), coverage.substituteCode, coverage.substituteReadyAt,
                    coverage.unresolved));
        }
        if (coverage.remaining.signum() <= 0) {
            state.readyDates.add(coverage.readyAt == null ? state.baseDate : coverage.readyAt);
            return;
        }
        if (bom == null) {
            if (coverage.unresolved) {
                state.unresolved = true;
            } else {
                state.readyDates.add(coverage.readyAt == null ? state.baseDate : coverage.readyAt);
            }
            return;
        }
        if (level >= MAX_BOM_DEPTH) {
            state.unresolved = true;
            state.limitFlags.add(FLAG_BOM_DEPTH_LIMIT);
            state.extraEvidence.add(evidence("BOM_DEPTH_LIMIT",
                    "BOM 展开达到 " + MAX_BOM_DEPTH + " 层仍未齐套，已停止：" + material.getCode()));
            return;
        }
        if (!expand(material, demand, level, state, next, coverage.remaining)) {
            state.unresolved = true;
            state.limitFlags.add(FLAG_MISSING_MASTER_DATA);
            state.extraEvidence.add(evidence("MISSING_MASTER_DATA",
                    "BOM " + material.getCode() + " 无有效子件，无法判定可齐日"));
        }
    }

    /**
     * 计算单层物料的净需求、替代分配与可齐日；不在这里展开子件。
     */
    private Coverage cover(Demand demand, Material material, LevelView view, GateState state) {
        InventoryAggregate aggregate = view.inventory.getOrDefault(demand.materialId,
                InventoryAggregate.EMPTY);
        List<TransitLine> transits = view.transit.getOrDefault(demand.materialId, List.of());
        BigDecimal required = demand.required;
        BigDecimal onHand = qty(aggregate.onHand);
        BigDecimal safety = qty(aggregate.safetyStock);
        BigDecimal transitAtRequest = qty(transitBefore(transits, state.requestedDate));
        BigDecimal availableAtRequest = onHand.add(transitAtRequest).subtract(safety).max(ZERO);
        BigDecimal shortage = required.subtract(availableAtRequest).max(ZERO);
        BigDecimal substituteCovered = ZERO;
        LocalDate substituteReadyAt = null;
        String substituteCode = null;
        if (shortage.signum() > 0 && state.allowSubstitute) {
            Allocation allocation = allocateSubstitutes(demand, material, shortage, view, state);
            substituteCovered = allocation.covered;
            substituteReadyAt = allocation.readyAt;
            substituteCode = allocation.code;
            state.substituteLines.addAll(allocation.lines);
        }
        BigDecimal remaining = shortage.subtract(substituteCovered).max(ZERO);
        LocalDate readyAt = null;
        boolean unresolved = false;
        if (remaining.signum() <= 0) {
            if (substituteCovered.signum() > 0) {
                readyAt = laterOf(substituteReadyAt, state.baseDate);
            } else {
                readyAt = earliestCoverDate(onHand, safety, transits, required, state.baseDate);
            }
        } else {
            readyAt = earliestCoverDate(onHand, safety, transits, remaining, state.baseDate);
            if (readyAt == null) {
                unresolved = true;
            }
        }
        return new Coverage(availableAtRequest, shortage, onHand, transitAtRequest, safety,
                earliestArrival(transits), substituteCovered, remaining, substituteCode,
                substituteReadyAt, unresolved, readyAt);
    }

    private Allocation allocateSubstitutes(Demand demand, Material material, BigDecimal shortage,
                                           LevelView view, GateState state) {
        if (demand.substitutes.isEmpty()) {
            return Allocation.EMPTY;
        }
        BigDecimal need = shortage;
        BigDecimal covered = ZERO;
        LocalDate readyAt = null;
        String appliedCode = null;
        List<SubstituteLine> lines = new ArrayList<>();
        List<SubCandidate> candidates = demand.substitutes.values().stream()
                .sorted(Comparator.comparing(SubCandidate::priority, PRIORITY_ORDER)
                        .thenComparing(SubCandidate::code)).toList();
        for (SubCandidate candidate : candidates) {
            Material substitute = view.substituteMaterials.get(candidate.code);
            UUID substituteId = substitute == null ? null : (UUID) substitute.getId();
            InventoryAggregate aggregate = substituteId == null ? InventoryAggregate.EMPTY
                    : view.inventory.getOrDefault(substituteId, InventoryAggregate.EMPTY);
            List<TransitLine> transits = substituteId == null ? List.of()
                    : view.transit.getOrDefault(substituteId, List.of());
            BigDecimal available = qty(aggregate.onHand)
                    .add(qty(transitBefore(transits, state.requestedDate)))
                    .subtract(qty(aggregate.safetyStock)).max(ZERO);
            BigDecimal applied = available.min(need);
            LocalDate candidateReady = applied.signum() > 0
                    ? earliestCoverDate(qty(aggregate.onHand), qty(aggregate.safetyStock), transits,
                            applied, state.baseDate)
                    : null;
            lines.add(new SubstituteLine(material.getCode(), candidate.code, candidate.priority,
                    candidate.ratio, qty(available), qty(applied), applied.signum() > 0,
                    candidateReady));
            if (applied.signum() > 0) {
                need = need.subtract(applied);
                covered = covered.add(applied);
                readyAt = latestOf(readyAt, candidateReady, state.baseDate);
                appliedCode = appliedCode == null ? candidate.code : appliedCode;
            }
            if (need.signum() <= 0) {
                break;
            }
        }
        return new Allocation(qty(covered), readyAt, appliedCode, lines);
    }

    private LevelView loadLevel(Set<UUID> materialIds, Map<UUID, Demand> demands, GateState state) {
        Map<UUID, Material> materials = new HashMap<>(dataReader.findMaterials(materialIds));
        Map<String, Material> substituteMaterials = new HashMap<>();
        Set<UUID> inventoryIds = new LinkedHashSet<>(materialIds);
        Set<String> candidateCodes = new LinkedHashSet<>();
        for (Demand demand : demands.values()) {
            candidateCodes.addAll(demand.substitutes.keySet());
        }
        if (!candidateCodes.isEmpty()) {
            substituteMaterials.putAll(dataReader.findMaterialsByCodes(candidateCodes));
            for (Material substitute : substituteMaterials.values()) {
                if (substitute != null && substitute.getId() != null) {
                    inventoryIds.add((UUID) substitute.getId());
                }
            }
        }
        Map<UUID, InventoryAggregate> inventory = new HashMap<>();
        Map<UUID, List<TransitLine>> transit = new HashMap<>();
        if (inventoryEnabled) {
            for (Inventory row : dataReader.findInventory(inventoryIds)) {
                InventoryAggregate aggregate = inventory.computeIfAbsent(row.getMaterialId(),
                        key -> new InventoryAggregate());
                aggregate.onHand = aggregate.onHand.add(nullSafe(row.getOnHandQty()));
                aggregate.safetyStock = aggregate.safetyStock.add(nullSafe(row.getSafetyStock()));
            }
            for (InventoryTransit row : dataReader.findTransit(inventoryIds)) {
                if (TRANSIT_CANCELLED.equals(row.getStatus())) {
                    continue;
                }
                if (row.getExpectedDate() == null) {
                    state.limitFlags.add(FLAG_UNDATED_TRANSIT);
                    continue;
                }
                transit.computeIfAbsent(row.getMaterialId(), key -> new ArrayList<>())
                        .add(new TransitLine(row.getExpectedDate(), nullSafe(row.getQty())));
            }
            transit.values().forEach(list -> list.sort(Comparator.comparing(TransitLine::date)));
        }
        return new LevelView(materials, substituteMaterials, inventory, transit);
    }

    private boolean expand(Material parent, Demand demand, int level, GateState state,
                           Map<UUID, Demand> next, BigDecimal requiredQty) {
        Bom bom = findActiveBom(demand.materialId, state);
        if (bom == null) {
            return false;
        }
        state.bomDepth = Math.max(state.bomDepth, level + 1);
        boolean added = false;
        for (BomItem item : findItems(bom, state)) {
            UUID childId = item.getChildMaterialId();
            if (childId == null) {
                state.missingData = true;
                state.unresolved = true;
                state.limitFlags.add(FLAG_MISSING_MASTER_DATA);
                state.extraEvidence.add(evidence("MISSING_MASTER_DATA",
                        "BOM 明细缺少子件主键：" + parent.getCode()));
                continue;
            }
            if (demand.ancestors.contains(childId)) {
                state.unresolved = true;
                state.limitFlags.add(FLAG_BOM_CYCLE);
                state.extraEvidence.add(evidence("BOM_CYCLE",
                        "BOM 成环已停止展开：" + joinPath(demand, childId)));
                continue;
            }
            BigDecimal scrap = item.getScrapRate() == null ? ZERO : item.getScrapRate();
            BigDecimal factor = ONE.add(scrap.divide(HUNDRED, QTY_SCALE, RoundingMode.HALF_UP));
            BigDecimal childRequired = qty(requiredQty.multiply(item.getQtyPerUnit()).multiply(factor));
            Demand child = next.get(childId);
            if (child == null) {
                List<UUID> ancestors = new ArrayList<>(demand.ancestors);
                ancestors.add(childId);
                child = new Demand(childId, ZERO, level + 1, ancestors);
                child.phantom = Boolean.TRUE.equals(item.getIsPhantom());
                next.put(childId, child);
            }
            child.required = child.required.add(childRequired);
            registerSubstitutes(child, item.getSubstitutes());
            added = true;
        }
        return added;
    }

    private List<Map<String, Object>> incomingSubstitutes(Set<UUID> childIds) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BomItem item : dataReader.findIncomingItems(childIds)) {
            if (item.getSubstitutes() != null) {
                rows.addAll(item.getSubstitutes());
            }
        }
        return rows;
    }

    private void registerSubstitutes(Demand demand, List<Map<String, Object>> rawSubstitutes) {
        if (rawSubstitutes == null || rawSubstitutes.isEmpty()) {
            return;
        }
        for (Map<String, Object> raw : rawSubstitutes) {
            if (raw == null) {
                continue;
            }
            String code = asText(raw.get("materialCode"));
            if (code == null || code.isBlank()) {
                continue;
            }
            Integer priority = asInteger(raw.get("priority"));
            String ratio = asText(raw.get("ratio"));
            SubCandidate existing = demand.substitutes.get(code);
            if (existing == null || comparePriority(priority, existing.priority) < 0) {
                demand.substitutes.put(code, new SubCandidate(code, priority, ratio));
            }
        }
    }

    private static int comparePriority(Integer left, Integer right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return Integer.compare(left, right);
    }

    private Bom findActiveBom(UUID parentMaterialId, GateState state) {
        return state.activeBoms.computeIfAbsent(parentMaterialId, key -> {
            List<Bom> boms = dataReader.findActiveBoms(key);
            return boms.isEmpty() ? null : boms.get(0);
        });
    }

    private List<BomItem> findItems(Bom bom, GateState state) {
        UUID bomId = (UUID) bom.getId();
        return state.bomItems.computeIfAbsent(bomId, key -> dataReader.findBomItems(key));
    }

    private Result buildResult(GateState state) {
        LocalDate readyAt = null;
        if (!state.unresolved && state.shortages.stream().noneMatch(line -> line.unresolved)
                && !state.readyDates.isEmpty() && !state.readyDates.contains(null)) {
            readyAt = state.readyDates.stream().max(LocalDate::compareTo).orElse(null);
        }
        List<Map<String, Object>> evidence = buildEvidence(state, readyAt);
        return new Result(readyAt, state.bomDepth, state.missingData,
                state.limitFlags.contains(FLAG_BOM_DEPTH_LIMIT),
                state.limitFlags.contains(FLAG_BOM_CYCLE), List.copyOf(state.limitFlags),
                List.copyOf(state.shortages), List.copyOf(state.substituteLines), evidence,
                assumptions(state));
    }

    private List<Map<String, Object>> buildEvidence(GateState state, LocalDate readyAt) {
        List<Map<String, Object>> items = new ArrayList<>(state.extraEvidence);
        List<ShortageLine> ordered = state.shortages.stream()
                .filter(line -> line.shortageQty.signum() > 0)
                .sorted(Comparator.comparing(ShortageLine::unresolved).reversed()
                        .thenComparing(ShortageLine::remainingQty, Comparator.reverseOrder())
                        .thenComparing(ShortageLine::materialCode))
                .toList();
        for (ShortageLine line : ordered) {
            Map<String, Object> item = evidence("MATERIAL_SHORTAGE", shortageDetail(line));
            item.put("materialCode", line.materialCode);
            item.put("level", line.level);
            item.put("requiredQty", line.requiredQty);
            item.put("availableQty", line.availableQty);
            item.put("shortageQty", line.shortageQty);
            item.put("remainingShortageQty", line.remainingQty);
            item.put("expectedArrival", line.expectedArrival);
            item.put("unresolved", line.unresolved);
            item.put("resolvedBySubstitute", line.substituteCoveredQty.signum() > 0);
            items.add(item);
        }
        for (SubstituteLine line : state.substituteLines) {
            if (line.applied()) {
                items.add(substituteEvidence(line));
            }
        }
        if (readyAt != null) {
            items.add(evidence("MATERIAL_READY", "物料可齐日 " + readyAt
                    + "（库存/在途/替代判定通过，产能尚未验证）"));
        }
        return cap(items);
    }

    private Map<String, Object> substituteEvidence(SubstituteLine line) {
        Map<String, Object> item = evidence("SUBSTITUTE_COVERED",
                "缺料物料 " + line.materialCode + " 使用替代料 " + line.substituteCode
                        + "（优先级 " + line.priority + "，比例 " + line.ratio + "）可用 "
                        + qtyText(line.availableQty) + "，覆盖 " + qtyText(line.coveredQty)
                        + (line.readyAt == null ? "" : "，替代后可齐日 " + line.readyAt));
        item.put("materialCode", line.materialCode);
        item.put("substituteCode", line.substituteCode);
        item.put("priority", line.priority);
        item.put("ratio", line.ratio);
        item.put("availableQty", line.availableQty);
        item.put("coveredQty", line.coveredQty);
        return item;
    }

    private Map<String, Object> evidence(String kind, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", kind);
        item.put("taskIds", List.of());
        item.put("resourceCode", null);
        item.put("start", null);
        item.put("end", null);
        item.put("detail", detail);
        return item;
    }

    private List<Map<String, Object>> cap(List<Map<String, Object>> items) {
        if (items.size() <= MAX_EVIDENCE) {
            return List.copyOf(items);
        }
        List<Map<String, Object>> capped = new ArrayList<>(items.subList(0, MAX_EVIDENCE - 1));
        capped.add(evidence("EVIDENCE_TRUNCATED",
                "另有 " + (items.size() - MAX_EVIDENCE + 1) + " 条证据未展示（已按严重度截断）"));
        return List.copyOf(capped);
    }

    private String shortageDetail(ShortageLine line) {
        StringBuilder builder = new StringBuilder();
        builder.append("第 ").append(line.level).append(" 层物料 ").append(line.materialCode)
                .append(" 需求 ").append(qtyText(line.requiredQty))
                .append("，可用 ").append(qtyText(line.availableQty))
                .append("（库存 ").append(qtyText(line.onHandQty))
                .append(" + 交期前在途 ").append(qtyText(line.transitQty))
                .append(" - 安全库存 ").append(qtyText(line.safetyStockQty)).append("）")
                .append("，缺口 ").append(qtyText(line.shortageQty));
        if (line.expectedArrival != null) {
            builder.append("；最早在途预计到货 ").append(line.expectedArrival.toLocalDate());
        } else {
            builder.append("；无未取消在途");
        }
        if (line.substituteCoveredQty.signum() > 0) {
            builder.append("；替代料 ").append(line.substituteCode).append(" 覆盖 ")
                    .append(qtyText(line.substituteCoveredQty)).append("，剩余缺口 ")
                    .append(qtyText(line.remainingQty));
        }
        if (line.unresolved) {
            builder.append("；无充足替代与在途，不可齐");
        } else if (line.remainingQty.signum() > 0) {
            builder.append("；可继续展开子件或等待在途");
        }
        return builder.toString();
    }

    private List<String> assumptions(GateState state) {
        List<String> list = new ArrayList<>();
        list.add("ORDER_LEVEL_MATERIAL_GATE：所有组件按首工序前齐套判断，不支持边做边等料。");
        list.add("NO_RECURSIVE_MAKE：自制件不递归排产，只按现有库存、在途与替代料判断。");
        list.add("TRANSIT_GATED_BY_REQUEST_DATE：在途仅在预计到货日不晚于请求交期时计入可用量（C49 口径）。");
        list.add("SAFETY_STOCK_RESERVED：安全库存视为不可动用储备。");
        list.add(state.allowSubstitute
                ? "MATERIAL_POLICY_SUBSTITUTE：允许使用 bom_item.substitutes 登记的替代料。"
                : "MATERIAL_POLICY_STRICT：未使用替代料，缺口按不可齐判定。");
        if (!inventoryEnabled) {
            list.add("INVENTORY_DISABLED：库存读取关闭，可用量按 0 计算。");
        }
        return List.copyOf(list);
    }

    private static OffsetDateTime earliestArrival(List<TransitLine> transits) {
        LocalDate earliest = transits.stream().map(TransitLine::date).min(LocalDate::compareTo)
                .orElse(null);
        return earliest == null ? null
                : OffsetDateTime.of(earliest, LocalTime.MIDNIGHT, ZoneOffset.UTC);
    }

    private static BigDecimal transitBefore(List<TransitLine> transits, LocalDate requestedDate) {
        BigDecimal total = ZERO;
        for (TransitLine line : transits) {
            if (!line.date.isAfter(requestedDate)) {
                total = total.add(line.qty);
            }
        }
        return total;
    }

    /**
     * 最早可齐日：现有库存 + 按日期累计的在途达到目标量的日期。
     *
     * @return 现成即可满足时返回 {@code baseDate}；按现有在途永远无法满足时返回 {@code null}
     */
    private static LocalDate earliestCoverDate(BigDecimal onHand, BigDecimal safety,
                                               List<TransitLine> transits, BigDecimal target,
                                               LocalDate baseDate) {
        BigDecimal running = onHand.subtract(safety);
        if (running.compareTo(target) >= 0) {
            return baseDate;
        }
        for (TransitLine line : transits) {
            running = running.add(line.qty);
            if (running.compareTo(target) >= 0) {
                return line.date;
            }
        }
        return null;
    }

    private static LocalDate laterOf(LocalDate date, LocalDate baseDate) {
        return date == null ? null : (date.isBefore(baseDate) ? baseDate : date);
    }

    private static LocalDate latestOf(LocalDate first, LocalDate second, LocalDate baseDate) {
        LocalDate max = first;
        if (second != null && (max == null || second.isAfter(max))) {
            max = second;
        }
        return laterOf(max, baseDate);
    }

    private static String qtyText(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal qty(BigDecimal value) {
        return value == null ? ZERO : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static String codeOf(Material material, UUID fallbackId) {
        return material == null ? fallbackId.toString() : material.getCode();
    }

    private static String joinPath(Demand demand, UUID childId) {
        StringBuilder builder = new StringBuilder();
        demand.ancestors.forEach(id -> builder.append(id).append('>'));
        return builder.append(childId).toString();
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value).trim();
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 物料门结果。
     *
     * @param materialReadyAt 所有层级都能齐的最早日期；不可判定时为 null
     * @param bomDepth 实际展开层数
     * @param missingMasterData 主数据缺失导致无法判定
     * @param depthLimited 触及深度上限
     * @param cycleDetected BOM 成环
     * @param limitFlags 可识别的限制标记
     * @param shortages 缺料明细
     * @param substitutes 替代料判定明细
     * @param evidence 证据（≤8 条）
     * @param assumptions 物料侧假设
     */
    public record Result(
            LocalDate materialReadyAt,
            int bomDepth,
            boolean missingMasterData,
            boolean depthLimited,
            boolean cycleDetected,
            List<String> limitFlags,
            List<ShortageLine> shortages,
            List<SubstituteLine> substitutes,
            List<Map<String, Object>> evidence,
            List<String> assumptions) {
    }

    /**
     * 单条缺料明细。
     *
     * @param materialCode 物料编码
     * @param materialName 物料名称
     * @param level BOM 层级（直接需求为 0）
     * @param requiredQty 需求数量
     * @param availableQty 需求日前可用量
     * @param shortageQty 需求日前缺口
     * @param onHandQty 现有库存
     * @param transitQty 需求日前的未取消在途
     * @param safetyStockQty 安全库存
     * @param expectedArrival 最早在途预计到货（可能晚于需求日）
     * @param substituteCoveredQty 替代覆盖量
     * @param remainingQty 替代后剩余缺口
     * @param substituteCode 首个生效替代料编码
     * @param substituteReadyAt 替代覆盖后的可齐日
     * @param unresolved 是否无法判定可齐
     */
    public record ShortageLine(
            String materialCode,
            String materialName,
            int level,
            BigDecimal requiredQty,
            BigDecimal availableQty,
            BigDecimal shortageQty,
            BigDecimal onHandQty,
            BigDecimal transitQty,
            BigDecimal safetyStockQty,
            OffsetDateTime expectedArrival,
            BigDecimal substituteCoveredQty,
            BigDecimal remainingQty,
            String substituteCode,
            LocalDate substituteReadyAt,
            boolean unresolved) {
    }

    /**
     * 单条替代料判定明细。
     *
     * @param materialCode 被替代的缺料物料编码
     * @param substituteCode 替代料编码
     * @param priority 优先级
     * @param ratio 替代比例
     * @param availableQty 替代料可用量（C49 同口径）
     * @param coveredQty 实际覆盖量
     * @param applied 是否被采用
     * @param readyAt 替代料可齐日
     */
    public record SubstituteLine(
            String materialCode,
            String substituteCode,
            Integer priority,
            String ratio,
            BigDecimal availableQty,
            BigDecimal coveredQty,
            boolean applied,
            LocalDate readyAt) {
    }

    private record Coverage(
            BigDecimal availableAtRequest,
            BigDecimal shortage,
            BigDecimal onHand,
            BigDecimal transitAtRequest,
            BigDecimal safetyStock,
            OffsetDateTime expectedArrival,
            BigDecimal substituteCovered,
            BigDecimal remaining,
            String substituteCode,
            LocalDate substituteReadyAt,
            boolean unresolved,
            LocalDate readyAt) {
    }

    private record Allocation(BigDecimal covered, LocalDate readyAt, String code,
                              List<SubstituteLine> lines) {

        private static final Allocation EMPTY = new Allocation(ZERO, null, null, List.of());
    }

    private record TransitLine(LocalDate date, BigDecimal qty) {
    }

    private record SubCandidate(String code, Integer priority, String ratio) {
    }

    private record LevelView(Map<UUID, Material> materials, Map<String, Material> substituteMaterials,
                             Map<UUID, InventoryAggregate> inventory,
                             Map<UUID, List<TransitLine>> transit) {
    }

    private static final class InventoryAggregate {

        private static final InventoryAggregate EMPTY = new InventoryAggregate();

        private BigDecimal onHand = ZERO;

        private BigDecimal safetyStock = ZERO;
    }

    private static final class Demand {

        private final UUID materialId;

        private BigDecimal required;

        private final int level;

        private final List<UUID> ancestors;

        private final Map<String, SubCandidate> substitutes = new LinkedHashMap<>();

        private boolean phantom;

        private Demand(UUID materialId, BigDecimal required, int level, List<UUID> ancestors) {
            this.materialId = materialId;
            this.required = required;
            this.level = level;
            this.ancestors = ancestors;
        }
    }

    private static final class GateState {

        private final LocalDate requestedDate;

        private final LocalDate baseDate;

        private final boolean allowSubstitute;

        private final Set<String> limitFlags = new LinkedHashSet<>();

        private final List<ShortageLine> shortages = new ArrayList<>();

        private final List<SubstituteLine> substituteLines = new ArrayList<>();

        private final List<Map<String, Object>> extraEvidence = new ArrayList<>();

        private final List<LocalDate> readyDates = new ArrayList<>();

        private final Map<UUID, Bom> activeBoms = new HashMap<>();

        private final Map<UUID, List<BomItem>> bomItems = new HashMap<>();

        private int bomDepth;

        private boolean unresolved;

        private boolean missingData;

        private GateState(LocalDate requestedDate, LocalDate baseDate, boolean allowSubstitute) {
            this.requestedDate = requestedDate;
            this.baseDate = baseDate;
            this.allowSubstitute = allowSubstitute;
        }
    }
}
