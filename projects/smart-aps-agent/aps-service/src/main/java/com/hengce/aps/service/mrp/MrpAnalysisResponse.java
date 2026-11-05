package com.hengce.aps.service.mrp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MRP 齐套分析结果（C25）。
 *
 * <p>顶层给出整体分级与按物料聚合的缺料明细；{@code orders} 给出按订单拆分的齐套状态，
 * 便于前端直接渲染“哪张订单缺什么料”。{@code availableQty = onHand + transit - safetyStock}，
 * 可为负（表示库存低于安全库存）。</p>
 *
 * @param sandboxId 排产场景标识（原样回显）
 * @param analyzedAt 分析时间（UTC）
 * @param status 齐套分级 FULL / PARTIAL / SHORTAGE
 * @param blocking 请求是否要求按缺料阻塞排产
 * @param blocked 是否实际阻塞（blocking=true 且存在缺料）
 * @param inventoryEnabled 库存表读取开关是否开启
 * @param orderCount 参与分析的订单数
 * @param warnings 展开过程中的非致命提示（无 ACTIVE BOM、环引用等）
 * @param orders 按订单拆分的齐套结果
 * @param shortages 按物料聚合的缺料明细
 */
public record MrpAnalysisResponse(UUID sandboxId, OffsetDateTime analyzedAt, MrpStatus status,
                                  boolean blocking, boolean blocked, boolean inventoryEnabled,
                                  int orderCount, List<String> warnings, List<MrpOrderResult> orders,
                                  List<MrpShortage> shortages) {

    /**
     * 单张订单的齐套结果。
     *
     * <p>保留既有 {@code kitted}/{@code shortages} 命名；契约侧将新增冻结条款映射
     * {@code status ← kitted}、{@code shortageCount ← shortages.length}。</p>
     *
     * @param orderNo 订单号
     * @param productCode 产品物料编码
     * @param qty 订单数量
     * @param dueDate 交期
     * @param kitted 是否齐套（既有布尔语义不变：{@code shortages} 为空即 true）
     * @param kittingStatus 三态齐套结论 FULL / PARTIAL / SHORTAGE
     * @param shortageItemCount 缺料物料项数
     * @param totalItemCount 该订单展开后的物料项总数
     * @param shortages 该订单的缺料明细
     */
    public record MrpOrderResult(String orderNo, String productCode, BigDecimal qty, LocalDate dueDate,
                                 boolean kitted, MrpStatus kittingStatus, int shortageItemCount,
                                 int totalItemCount, List<MrpOrderShortage> shortages) {
    }

    /**
     * 订单级缺料明细。
     *
     * @param materialCode 物料编码
     * @param materialName 物料名称
     * @param requiredQty 该订单需求数量（已含 BOM 损耗）
     * @param allocatedQty 分配到该订单的可用数量
     * @param shortageQty 短缺数量
     */
    public record MrpOrderShortage(String materialCode, String materialName, BigDecimal requiredQty,
                                   BigDecimal allocatedQty, BigDecimal shortageQty) {
    }

    /**
     * 按物料聚合的缺料明细（C25 §24.7：必须暴露 onHandQty/transitQty/safetyStock）。
     *
     * @param materialCode 物料编码
     * @param materialName 物料名称
     * @param requiredQty 跨订单毛需求合计
     * @param onHandQty 现有库存合计
     * @param transitQty 在途库存合计（仅计入需求时点前的非取消在途）
     * @param safetyStock 安全库存合计
     * @param availableQty 可用量 = onHand + transit - safetyStock（可为负，短缺计算按 0 下限）
     * @param shortageQty 短缺数量
     * @param earliestRequiredAt 最早需求时间（最早订单交期当日 00:00 UTC）
     * @param affectedOrderNos 受影响订单号
     * @param suggestedPurchaseDate 建议下单日期（includeSuggestions=true 时输出）
     * @param suggestedQty 建议采购数量（includeSuggestions=true 时输出）
     * @param supplierCode 物料主数据的默认供应商编码；无默认供应商时为 null
     * @param substitutes 该物料的替代料候选（来自 BOM 明细 substitutes JSONB），无替代料时为空数组
     * @param expectedArrival 最早的非取消在途预计到货日（00:00 UTC）；无在途时为 null。
     *     该日期晚于最早需求日时只作预告，不计入 {@code transitQty}（"给了日期但未冲抵缺口"）
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MrpShortage(String materialCode, String materialName, BigDecimal requiredQty,
                              BigDecimal onHandQty, BigDecimal transitQty, BigDecimal safetyStock,
                              BigDecimal availableQty, BigDecimal shortageQty,
                              OffsetDateTime earliestRequiredAt, List<String> affectedOrderNos,
                              OffsetDateTime suggestedPurchaseDate, BigDecimal suggestedQty,
                              String supplierCode, List<MrpSubstitute> substitutes,
                              OffsetDateTime expectedArrival) {
    }

    /**
     * 替代料候选（C49 §25.24）。
     *
     * @param materialCode 替代料物料编码
     * @param priority 替代优先级（BOM substitutes JSONB 的 priority，数值越小越优先）
     * @param ratio 替代比例（BOM substitutes JSONB 的 ratio，原样输出，如 {@code 1:1}）
     * @param availableQty 替代料可用量 = max(0, 现有库存 + 需求时点前在途 - 安全库存)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MrpSubstitute(String materialCode, Integer priority, String ratio,
                                BigDecimal availableQty) {
    }

    /**
     * 采购建议项（C34 §25.6：字段与既有 {@link MrpShortage} 一致，但不得携带库存明细字段）。
     *
     * @param materialCode 物料编码
     * @param materialName 物料名称
     * @param requiredQty 跨订单毛需求合计
     * @param availableQty 可用量
     * @param shortageQty 短缺数量
     * @param earliestRequiredAt 最早需求时间
     * @param affectedOrderNos 受影响订单号
     * @param suggestedPurchaseDate 建议下单日期
     * @param suggestedQty 建议采购数量
     * @param supplierCode 建议供应商编码
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MrpSuggestionView(String materialCode, String materialName, BigDecimal requiredQty,
                                    BigDecimal availableQty, BigDecimal shortageQty,
                                    OffsetDateTime earliestRequiredAt, List<String> affectedOrderNos,
                                    OffsetDateTime suggestedPurchaseDate, BigDecimal suggestedQty,
                                    String supplierCode) {

        /**
         * 从齐套缺料明细投影为采购建议项。
         *
         * @param shortage 齐套缺料明细
         * @return 采购建议项
         */
        public static MrpSuggestionView from(MrpShortage shortage) {
            return new MrpSuggestionView(shortage.materialCode(), shortage.materialName(),
                    shortage.requiredQty(), shortage.availableQty(), shortage.shortageQty(),
                    shortage.earliestRequiredAt(), shortage.affectedOrderNos(),
                    shortage.suggestedPurchaseDate(), shortage.suggestedQty(), shortage.supplierCode());
        }
    }
}
