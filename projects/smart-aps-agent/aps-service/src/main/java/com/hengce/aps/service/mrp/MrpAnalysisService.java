package com.hengce.aps.service.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.bom.Bom;
import com.hengce.aps.infrastructure.persistence.bom.BomItem;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.mrp.Inventory;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryMapper;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryTransit;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryTransitMapper;
import com.hengce.aps.infrastructure.persistence.mrp.MrpMasterDataRepository;
import com.hengce.aps.infrastructure.persistence.order.Order;
import com.hengce.aps.infrastructure.persistence.order.OrderMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * MRP 齐套分析：BOM 多级展开 → 毛需求 → 净需求（扣库存/在途/安全库存）→ 分级与缺料明细。
 *
 * <p>算法要点：</p>
 * <ul>
 *   <li>仅使用 {@code status=ACTIVE} 的 BOM；SMT 拼板、虚拟件按 {@code is_phantom} 处理：
 *       虚拟件自身不计需求，但其子件按含损耗的展开数量继续计入。</li>
 *   <li>需求数量 = 父件需求 × 单件用量 × (1 + scrapRate/100)，保留 6 位小数。</li>
 *   <li>可用量 = Σ现有库存 + Σ在途 - Σ安全库存；安全库存视为不可动用储备，短缺计算时可用量按 0 下限。
 *       在途按 C25 §24.7 只计 {@code status=OPEN} 且 {@code expected_date} 不晚于该物料最早需求日；
 *       V2 的 {@code OPEN} 等价状态为 {@code PLANNED/CONFIRMED}（V2 CHECK 不允许 OPEN/ARRIVED）。</li>
 *   <li>跨订单按“交期升序 → 优先级 → 订单号”顺序分配可用量，再据此判定每张订单是否齐套：
 *       全部齐套 = FULL，全部缺料 = SHORTAGE，其余 = PARTIAL。</li>
 *   <li>BOM 深度上限 10 层，检测到环或超深只记 warning，不中断整次分析。</li>
 * </ul>
 */
@Service
public class MrpAnalysisService {

    /**
     * BOM 最大展开层级。
     */
    static final int MAX_BOM_DEPTH = 10;

    /**
     * 数量统一保留的小数位。
     */
    static final int QTY_SCALE = 6;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final BigDecimal ONE = BigDecimal.ONE;

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static final String TRANSIT_CANCELLED = "CANCELLED";

    private static final String ORDER_CANCELLED = "CANCELLED";

    private static final String ORDER_COMPLETED = "COMPLETED";

    private static final List<String> ANALYZABLE_STATUSES =
            List.of("CONFIRMED", "SCHEDULED", "RELEASED", "IN_PROGRESS");

    private static final Map<String, Integer> PRIORITY_RANK =
            Map.of("URGENT", 0, "HIGH", 1, "NORMAL", 2, "LOW", 3);

    private final OrderMapper orderMapper;

    private final MrpMasterDataRepository masterDataRepository;

    private final InventoryMapper inventoryMapper;

    private final InventoryTransitMapper inventoryTransitMapper;

    private final boolean inventoryEnabled;

    /**
     * 构造服务。
     *
     * @param orderMapper 订单 Mapper（只读）
     * @param masterDataRepository BOM/物料只读仓库
     * @param inventoryMapper 库存 Mapper（只读）
     * @param inventoryTransitMapper 在途库存 Mapper（只读）
     * @param inventoryEnabled 库存读取开关，关闭时可用量按 0 计算（回滚点 R2）
     */
    public MrpAnalysisService(OrderMapper orderMapper, MrpMasterDataRepository masterDataRepository,
                              InventoryMapper inventoryMapper,
                              InventoryTransitMapper inventoryTransitMapper,
                              @Value("${aps.mrp.inventory-enabled:true}") boolean inventoryEnabled) {
        this.orderMapper = orderMapper;
        this.masterDataRepository = masterDataRepository;
        this.inventoryMapper = inventoryMapper;
        this.inventoryTransitMapper = inventoryTransitMapper;
        this.inventoryEnabled = inventoryEnabled;
    }

    /**
     * 执行 MRP 齐套分析。
     *
     * @param command 分析命令
     * @return 齐套分析结果
     */
    public MrpAnalysisResponse analyze(MrpAnalysisCommand command) {
        if (command == null || command.sandboxId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sandboxId 不能为空");
        }
        List<Order> orders = loadOrders(command.orderNos());
        Set<String> warnings = new LinkedHashSet<>();
        Map<String, Map<UUID, BigDecimal>> demandByOrder = new LinkedHashMap<>();
        Map<UUID, Map<String, SubstituteCandidate>> substitutesByMaterial = new HashMap<>();
        for (Order order : orders) {
            ExpansionContext context = new ExpansionContext(warnings, substitutesByMaterial);
            context.path.add(order.getProductId());
            expand(context, order.getProductId(), qty(order.getQty()), 1);
            demandByOrder.put(order.getOrderNo(), context.demand);
            if (context.demand.isEmpty()) {
                warnings.add("订单 " + order.getOrderNo() + " 未展开出任何物料需求（产品无 ACTIVE BOM）");
            }
        }

        Map<UUID, BigDecimal> gross = new HashMap<>();
        Map<UUID, Set<String>> affectedOrders = new HashMap<>();
        Map<UUID, LocalDate> earliestDue = new HashMap<>();
        for (Order order : orders) {
            Map<UUID, BigDecimal> demand = demandByOrder.getOrDefault(order.getOrderNo(), Map.of());
            for (Map.Entry<UUID, BigDecimal> entry : demand.entrySet()) {
                gross.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
                affectedOrders.computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>())
                        .add(order.getOrderNo());
                earliestDue.merge(entry.getKey(), order.getDueDate(), (left, right) ->
                        left.isBefore(right) ? left : right);
            }
        }

        // C49：替代料仍需按“父件最早需求日”裁剪在途，故把替代料主键并入库存查询集合，
        // 并把父件的最早需求日映射给替代料，避免晚于缺料时点的在途被误算为可用。
        Map<String, Material> substituteMaterials = loadSubstituteMaterials(substitutesByMaterial, warnings);
        Set<UUID> inventoryIds = new LinkedHashSet<>(gross.keySet());
        Map<UUID, LocalDate> inventoryDue = new HashMap<>(earliestDue);
        for (Map.Entry<UUID, Map<String, SubstituteCandidate>> entry : substitutesByMaterial.entrySet()) {
            LocalDate parentDue = earliestDue.get(entry.getKey());
            for (String substituteCode : entry.getValue().keySet()) {
                Material substitute = substituteMaterials.get(substituteCode);
                if (substitute == null || substitute.getId() == null) {
                    continue;
                }
                UUID substituteId = (UUID) substitute.getId();
                inventoryIds.add(substituteId);
                if (parentDue != null) {
                    inventoryDue.merge(substituteId, parentDue, (left, right) ->
                            left.isBefore(right) ? left : right);
                }
            }
        }

        Map<UUID, BigDecimal> onHand = new HashMap<>();
        Map<UUID, BigDecimal> safetyStock = new HashMap<>();
        Map<UUID, BigDecimal> transit = new HashMap<>();
        Map<UUID, LocalDate> earliestArrival = new HashMap<>();
        if (inventoryEnabled && !inventoryIds.isEmpty()) {
            loadInventory(inventoryIds, inventoryDue, onHand, safetyStock, transit, earliestArrival);
        } else if (!inventoryEnabled) {
            warnings.add("库存读取开关 aps.mrp.inventory-enabled=false，可用量按 0 计算");
        }

        Map<UUID, BigDecimal> available = new HashMap<>();
        for (UUID materialId : inventoryIds) {
            available.put(materialId, onHand.getOrDefault(materialId, ZERO)
                    .add(transit.getOrDefault(materialId, ZERO))
                    .subtract(safetyStock.getOrDefault(materialId, ZERO)));
        }

        Map<UUID, Material> materials = loadMaterials(gross.keySet(), orders);
        Map<UUID, String> supplierCodes = loadSupplierCodes(materials);
        List<Order> sortedOrders = new ArrayList<>(orders);
        sortedOrders.sort(Comparator.comparing(Order::getDueDate)
                .thenComparingInt(order -> order.getPriority() == null ? 2
                        : PRIORITY_RANK.getOrDefault(order.getPriority(), 2))
                .thenComparing(Order::getOrderNo));
        Map<String, Map<UUID, BigDecimal>> allocated = allocate(sortedOrders, demandByOrder, gross, available);

        List<MrpAnalysisResponse.MrpOrderResult> orderResults =
                buildOrderResults(sortedOrders, demandByOrder, allocated, materials);
        int kittedCount = (int) orderResults.stream().filter(MrpAnalysisResponse.MrpOrderResult::kitted).count();
        MrpStatus status = resolveStatus(orderResults.size(), kittedCount);
        List<MrpAnalysisResponse.MrpShortage> shortages = buildShortages(gross, onHand, safetyStock, transit,
                available, earliestDue, earliestArrival, affectedOrders, materials, supplierCodes,
                substitutesByMaterial, substituteMaterials, command.includeSuggestions());
        boolean blocked = command.blocking() && status != MrpStatus.FULL && !orderResults.isEmpty();

        return new MrpAnalysisResponse(command.sandboxId(), OffsetDateTime.now(ZoneOffset.UTC), status,
                command.blocking(), blocked, inventoryEnabled, orderResults.size(),
                List.copyOf(warnings), orderResults, shortages);
    }

    private List<Order> loadOrders(List<String> orderNos) {
        Set<String> requested = new LinkedHashSet<>();
        if (orderNos != null) {
            for (String orderNo : orderNos) {
                if (orderNo != null && !orderNo.isBlank()) {
                    requested.add(orderNo.trim());
                }
            }
        }
        LambdaQueryWrapper<Order> wrapper = Wrappers.lambdaQuery();
        if (requested.isEmpty()) {
            wrapper.in(Order::getStatus, ANALYZABLE_STATUSES);
        } else {
            wrapper.in(Order::getOrderNo, requested);
        }
        wrapper.orderByAsc(Order::getDueDate).orderByAsc(Order::getOrderNo);
        List<Order> orders = orderMapper.selectList(wrapper);
        if (!requested.isEmpty()) {
            Set<String> found = new HashSet<>();
            orders.forEach(order -> found.add(order.getOrderNo()));
            List<String> missing = requested.stream().filter(orderNo -> !found.contains(orderNo)).toList();
            if (!missing.isEmpty()) {
                throw new BizException(ErrorCode.NOT_FOUND, "订单不存在: " + String.join(", ", missing));
            }
            orders.removeIf(order -> ORDER_CANCELLED.equals(order.getStatus())
                    || ORDER_COMPLETED.equals(order.getStatus()));
        }
        return orders;
    }

    private void expand(ExpansionContext context, UUID parentMaterialId, BigDecimal parentQty, int level) {
        if (level > MAX_BOM_DEPTH) {
            context.warnings.add("BOM 展开超过 " + MAX_BOM_DEPTH + " 层已截断: " + parentMaterialId);
            return;
        }
        Bom bom = findActiveBom(context, parentMaterialId);
        if (bom == null) {
            return;
        }
        for (BomItem item : findItems(context, (UUID) bom.getId())) {
            UUID childId = item.getChildMaterialId();
            Material child = material(context, childId);
            if (child == null) {
                context.warnings.add("BOM 子件物料不存在: " + childId);
                continue;
            }
            BigDecimal scrap = item.getScrapRate() == null ? ZERO : item.getScrapRate();
            BigDecimal factor = ONE.add(scrap.divide(HUNDRED, QTY_SCALE, RoundingMode.HALF_UP));
            BigDecimal required = qty(parentQty.multiply(item.getQtyPerUnit()).multiply(factor));
            if (!Boolean.TRUE.equals(item.getIsPhantom())) {
                context.demand.merge(childId, required, BigDecimal::add);
                registerSubstitutes(context, childId, item);
            }
            if (context.path.contains(childId)) {
                context.warnings.add("BOM 存在循环引用已跳过: "
                        + joinPath(context.path, childId));
                continue;
            }
            if (findActiveBom(context, childId) != null) {
                context.path.add(childId);
                expand(context, childId, required, level + 1);
                context.path.remove(context.path.size() - 1);
            }
        }
    }

    private Map<String, Map<UUID, BigDecimal>> allocate(List<Order> sortedOrders,
                                                       Map<String, Map<UUID, BigDecimal>> demandByOrder,
                                                       Map<UUID, BigDecimal> gross,
                                                       Map<UUID, BigDecimal> available) {
        Map<UUID, BigDecimal> pool = new HashMap<>();
        for (UUID materialId : gross.keySet()) {
            pool.put(materialId, available.getOrDefault(materialId, ZERO).max(ZERO));
        }
        Map<String, Map<UUID, BigDecimal>> allocated = new HashMap<>();
        for (UUID materialId : gross.keySet()) {
            BigDecimal remaining = pool.get(materialId);
            for (Order order : sortedOrders) {
                BigDecimal required = demandByOrder.getOrDefault(order.getOrderNo(), Map.of())
                        .getOrDefault(materialId, ZERO);
                if (required.signum() <= 0 || remaining.signum() <= 0) {
                    continue;
                }
                BigDecimal granted = remaining.min(required);
                allocated.computeIfAbsent(order.getOrderNo(), key -> new HashMap<>())
                        .merge(materialId, granted, BigDecimal::add);
                remaining = remaining.subtract(granted);
            }
        }
        return allocated;
    }

    private List<MrpAnalysisResponse.MrpOrderResult> buildOrderResults(
            List<Order> orders, Map<String, Map<UUID, BigDecimal>> demandByOrder,
            Map<String, Map<UUID, BigDecimal>> allocated, Map<UUID, Material> materials) {
        List<MrpAnalysisResponse.MrpOrderResult> results = new ArrayList<>();
        for (Order order : orders) {
            Map<UUID, BigDecimal> demand = demandByOrder.getOrDefault(order.getOrderNo(), Map.of());
            Map<UUID, BigDecimal> granted = allocated.getOrDefault(order.getOrderNo(), Map.of());
            List<MrpAnalysisResponse.MrpOrderShortage> shortages = new ArrayList<>();
            for (Map.Entry<UUID, BigDecimal> entry : demand.entrySet()) {
                BigDecimal required = entry.getValue();
                BigDecimal allocatedQty = granted.getOrDefault(entry.getKey(), ZERO);
                BigDecimal shortage = required.subtract(allocatedQty);
                if (shortage.signum() > 0) {
                    Material material = materials.get(entry.getKey());
                    shortages.add(new MrpAnalysisResponse.MrpOrderShortage(codeOf(material, entry.getKey()),
                            nameOf(material), qty(required), qty(allocatedQty), qty(shortage)));
                }
            }
            shortages.sort(Comparator.comparing(MrpAnalysisResponse.MrpOrderShortage::materialCode));
            Material product = materials.get(order.getProductId());
            int totalItemCount = demand.size();
            int shortageItemCount = shortages.size();
            results.add(new MrpAnalysisResponse.MrpOrderResult(order.getOrderNo(),
                    codeOf(product, order.getProductId()), qty(order.getQty()), order.getDueDate(),
                    shortageItemCount == 0, resolveKittingStatus(totalItemCount, shortageItemCount),
                    shortageItemCount, totalItemCount, shortages));
        }
        return results;
    }

    /**
     * 订单级三态齐套：无缺料 = FULL；缺料项数等于物料项数 = SHORTAGE；其余 = PARTIAL。
     *
     * <p>{@code kitted} 布尔语义保持不变（{@code shortageItemCount == 0}），本方法只补充三态细分。</p>
     */
    private MrpStatus resolveKittingStatus(int totalItemCount, int shortageItemCount) {
        if (shortageItemCount <= 0) {
            return MrpStatus.FULL;
        }
        return shortageItemCount >= totalItemCount ? MrpStatus.SHORTAGE : MrpStatus.PARTIAL;
    }

    private List<MrpAnalysisResponse.MrpShortage> buildShortages(Map<UUID, BigDecimal> gross,
                                                                Map<UUID, BigDecimal> onHand,
                                                                Map<UUID, BigDecimal> safetyStock,
                                                                Map<UUID, BigDecimal> transit,
                                                                Map<UUID, BigDecimal> available,
                                                                Map<UUID, LocalDate> earliestDue,
                                                                Map<UUID, LocalDate> earliestArrival,
                                                                Map<UUID, Set<String>> affectedOrders,
                                                                Map<UUID, Material> materials,
                                                                Map<UUID, String> supplierCodes,
                                                                Map<UUID, Map<String, SubstituteCandidate>> substitutesByMaterial,
                                                                Map<String, Material> substituteMaterials,
                                                                boolean includeSuggestions) {
        List<MrpAnalysisResponse.MrpShortage> shortages = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : gross.entrySet()) {
            UUID materialId = entry.getKey();
            BigDecimal required = entry.getValue();
            BigDecimal availableQty = available.getOrDefault(materialId, ZERO);
            BigDecimal shortage = required.subtract(availableQty.max(ZERO));
            if (shortage.signum() <= 0) {
                continue;
            }
            Material material = materials.get(materialId);
            LocalDate dueDate = earliestDue.get(materialId);
            OffsetDateTime earliestRequiredAt = dueDate == null ? null
                    : OffsetDateTime.of(dueDate, LocalTime.MIDNIGHT, ZoneOffset.UTC);
            List<String> orderNos = new ArrayList<>(affectedOrders.getOrDefault(materialId, Set.of()));
            orderNos.sort(Comparator.naturalOrder());
            OffsetDateTime suggestedPurchaseDate = null;
            BigDecimal suggestedQty = null;
            if (includeSuggestions && earliestRequiredAt != null) {
                int leadTimeDays = material == null || material.getLeadTimeDays() == null
                        ? 0 : material.getLeadTimeDays();
                suggestedPurchaseDate = earliestRequiredAt.minusDays(leadTimeDays);
                suggestedQty = qty(shortage);
            }
            LocalDate arrivalDate = earliestArrival.get(materialId);
            OffsetDateTime expectedArrival = arrivalDate == null ? null
                    : OffsetDateTime.of(arrivalDate, LocalTime.MIDNIGHT, ZoneOffset.UTC);
            String supplierCode = material == null || material.getDefaultSupplierId() == null
                    ? null : supplierCodes.get(material.getDefaultSupplierId());
            List<MrpAnalysisResponse.MrpSubstitute> substitutes = buildSubstitutes(
                    substitutesByMaterial.get(materialId), substituteMaterials, available);
            shortages.add(new MrpAnalysisResponse.MrpShortage(codeOf(material, materialId), nameOf(material),
                    qty(required), qty(onHand.getOrDefault(materialId, ZERO)),
                    qty(transit.getOrDefault(materialId, ZERO)),
                    qty(safetyStock.getOrDefault(materialId, ZERO)),
                    qty(availableQty), qty(shortage), earliestRequiredAt, orderNos,
                    suggestedPurchaseDate, suggestedQty, supplierCode, substitutes, expectedArrival));
        }
        shortages.sort(Comparator.comparing(MrpAnalysisResponse.MrpShortage::materialCode));
        return shortages;
    }

    /**
     * 组装替代料候选：编码/优先级/比例来自 BOM 明细的 substitutes JSONB，
     * 可用量取与主物料同口径的 max(0, onHand + transit - safetyStock)。
     */
    private List<MrpAnalysisResponse.MrpSubstitute> buildSubstitutes(
            Map<String, SubstituteCandidate> candidates, Map<String, Material> substituteMaterials,
            Map<UUID, BigDecimal> available) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<MrpAnalysisResponse.MrpSubstitute> substitutes = new ArrayList<>();
        for (SubstituteCandidate candidate : candidates.values()) {
            Material material = substituteMaterials.get(candidate.materialCode());
            if (material == null || material.getId() == null) {
                continue;
            }
            BigDecimal substituteAvailable = available.getOrDefault((UUID) material.getId(), ZERO).max(ZERO);
            substitutes.add(new MrpAnalysisResponse.MrpSubstitute(candidate.materialCode(),
                    candidate.priority(), candidate.ratio(), qty(substituteAvailable)));
        }
        Comparator<Integer> priorityComparator = Comparator.nullsLast(Comparator.naturalOrder());
        substitutes.sort(Comparator.comparing(MrpAnalysisResponse.MrpSubstitute::priority, priorityComparator)
                .thenComparing(MrpAnalysisResponse.MrpSubstitute::materialCode));
        return substitutes;
    }

    private void loadInventory(Set<UUID> materialIds, Map<UUID, LocalDate> earliestDue,
                               Map<UUID, BigDecimal> onHand, Map<UUID, BigDecimal> safetyStock,
                               Map<UUID, BigDecimal> transit, Map<UUID, LocalDate> earliestArrival) {
        List<Inventory> inventories = inventoryMapper.selectList(Wrappers.<Inventory>lambdaQuery()
                .in(Inventory::getMaterialId, materialIds));
        for (Inventory inventory : inventories) {
            onHand.merge(inventory.getMaterialId(), nullSafe(inventory.getOnHandQty()), BigDecimal::add);
            safetyStock.merge(inventory.getMaterialId(), nullSafe(inventory.getSafetyStock()),
                    BigDecimal::add);
        }
        // C25 §24.7：只计需求时点前可到货的在途。V2 的 CHECK 不允许 OPEN/ARRIVED，
        // PLANNED/CONFIRMED 即等价于契约中的 OPEN；CANCELLED 一律不计入。
        List<InventoryTransit> transits = inventoryTransitMapper.selectList(
                Wrappers.<InventoryTransit>lambdaQuery()
                        .in(InventoryTransit::getMaterialId, materialIds)
                        .ne(InventoryTransit::getStatus, TRANSIT_CANCELLED));
        for (InventoryTransit record : transits) {
            LocalDate expectedDate = record.getExpectedDate();
            // C49：expectedArrival 取最早的非取消在途日期，晚于需求日也给出（仅作预告，不冲抵）。
            if (expectedDate != null) {
                earliestArrival.merge(record.getMaterialId(), expectedDate, (left, right) ->
                        left.isBefore(right) ? left : right);
            }
            LocalDate requiredDate = earliestDue.get(record.getMaterialId());
            if (requiredDate != null && expectedDate != null && expectedDate.isAfter(requiredDate)) {
                continue;
            }
            transit.merge(record.getMaterialId(), nullSafe(record.getQty()), BigDecimal::add);
        }
    }

    private Map<String, Material> loadSubstituteMaterials(
            Map<UUID, Map<String, SubstituteCandidate>> substitutesByMaterial, Set<String> warnings) {
        Set<String> codes = new HashSet<>();
        for (Map<String, SubstituteCandidate> candidates : substitutesByMaterial.values()) {
            codes.addAll(candidates.keySet());
        }
        Map<String, Material> materials = masterDataRepository.findMaterialsByCodes(codes);
        if (materials == null) {
            return Map.of();
        }
        for (String code : codes) {
            if (!materials.containsKey(code)) {
                warnings.add("BOM substitutes 引用的替代料不存在: " + code);
            }
        }
        return materials;
    }

    private Map<UUID, String> loadSupplierCodes(Map<UUID, Material> materials) {
        Set<UUID> supplierIds = new HashSet<>();
        for (Material material : materials.values()) {
            if (material != null && material.getDefaultSupplierId() != null) {
                supplierIds.add(material.getDefaultSupplierId());
            }
        }
        Map<UUID, String> codes = masterDataRepository.findSupplierCodes(supplierIds);
        return codes == null ? Map.of() : codes;
    }

    private void registerSubstitutes(ExpansionContext context, UUID childId, BomItem item) {
        List<Map<String, Object>> rawSubstitutes = item.getSubstitutes();
        if (rawSubstitutes == null || rawSubstitutes.isEmpty()) {
            return;
        }
        Map<String, SubstituteCandidate> candidates = context.substitutes
                .computeIfAbsent(childId, key -> new LinkedHashMap<>());
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
            SubstituteCandidate existing = candidates.get(code);
            if (existing == null || comparePriority(priority, existing.priority()) < 0) {
                candidates.put(code, new SubstituteCandidate(code, priority, ratio));
            }
        }
    }

    private int comparePriority(Integer left, Integer right) {
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

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value).trim();
    }

    private Integer asInteger(Object value) {
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

    private Map<UUID, Material> loadMaterials(Set<UUID> materialIds, List<Order> orders) {
        Set<UUID> ids = new HashSet<>(materialIds);
        orders.forEach(order -> ids.add(order.getProductId()));
        ids.remove(null);
        return masterDataRepository.findMaterials(ids);
    }

    private Bom findActiveBom(ExpansionContext context, UUID parentMaterialId) {
        if (context.activeBoms.containsKey(parentMaterialId)) {
            return context.activeBoms.get(parentMaterialId);
        }
        List<Bom> boms = masterDataRepository.findActiveBoms(parentMaterialId);
        Bom active = boms.isEmpty() ? null : boms.get(0);
        if (boms.size() > 1) {
            context.warnings.add("同一父件存在多个 ACTIVE BOM，取最新生效版本: " + parentMaterialId);
        }
        context.activeBoms.put(parentMaterialId, active);
        return active;
    }

    private List<BomItem> findItems(ExpansionContext context, UUID bomId) {
        return context.items.computeIfAbsent(bomId, masterDataRepository::findItems);
    }

    private Material material(ExpansionContext context, UUID materialId) {
        if (context.materials.containsKey(materialId)) {
            return context.materials.get(materialId);
        }
        Material material = masterDataRepository.findMaterial(materialId);
        context.materials.put(materialId, material);
        return material;
    }

    private MrpStatus resolveStatus(int orderCount, int kittedCount) {
        if (orderCount == 0 || kittedCount == orderCount) {
            return MrpStatus.FULL;
        }
        return kittedCount == 0 ? MrpStatus.SHORTAGE : MrpStatus.PARTIAL;
    }

    private String joinPath(List<UUID> path, UUID childId) {
        StringBuilder builder = new StringBuilder();
        for (UUID element : path) {
            builder.append(element).append('>');
        }
        return builder.append(childId).toString();
    }

    private String codeOf(Material material, UUID fallbackId) {
        return material == null ? fallbackId.toString() : material.getCode();
    }

    private String nameOf(Material material) {
        return material == null ? null : material.getName();
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal qty(BigDecimal value) {
        return value == null ? ZERO : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 单张订单的展开上下文：需求、路径与按需加载的 BOM/物料缓存。
     */
    private static final class ExpansionContext {

        private final Set<String> warnings;

        private final Map<UUID, BigDecimal> demand = new LinkedHashMap<>();

        private final List<UUID> path = new ArrayList<>();

        private final Map<UUID, Material> materials = new HashMap<>();

        private final Map<UUID, Bom> activeBoms = new HashMap<>();

        private final Map<UUID, List<BomItem>> items = new HashMap<>();

        private final Map<UUID, Map<String, SubstituteCandidate>> substitutes;

        private ExpansionContext(Set<String> warnings,
                                 Map<UUID, Map<String, SubstituteCandidate>> substitutes) {
            this.warnings = warnings;
            this.substitutes = substitutes;
        }
    }

    /**
     * BOM 明细 substitutes JSONB 中的一条替代料候选。
     */
    private record SubstituteCandidate(String materialCode, Integer priority, String ratio) {
    }
}
