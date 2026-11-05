/**
 * MRP 齐套分析契约（C25）。
 * 后端实现：aps-service/.../service/mrp/MrpAnalysisResponse.java。
 * 数量字段为 NUMERIC(18,4)，JSON 反序列化后是 number；这里同时兼容字符串，
 * 避免后端换序列化配置时前端静默丢精度。
 */

/** 齐套分级：FULL 齐套 / PARTIAL 部分齐套 / SHORTAGE 缺料。 */
export type MrpStatus = 'FULL' | 'PARTIAL' | 'SHORTAGE'

/** 数量原始值：后端 BigDecimal 序列化为 JSON number。 */
export type MrpQty = number | string

/** POST /mrp/analysis 入参。 */
export interface MrpAnalysisParams {
  sandboxId: string
  orderNos?: string[]
  includeSuggestions?: boolean
  blocking?: boolean
}

/** 订单级缺料明细。 */
export interface MrpOrderShortage {
  materialCode: string
  materialName?: string | null
  requiredQty: MrpQty
  allocatedQty: MrpQty
  shortageQty: MrpQty
}

/** 单张订单的齐套结果。 */
export interface MrpOrderResult {
  orderNo: string
  productCode?: string | null
  qty: MrpQty
  dueDate?: string | null
  kitted: boolean
  /** 订单级三态结论文本（C49）；旧响应可能缺省，用 kitted 兜底。 */
  kittingStatus?: MrpStatus | null
  /** 该订单缺料物料项数。 */
  shortageItemCount?: number | null
  /** 该订单展开后的物料项总数。 */
  totalItemCount?: number | null
  shortages: MrpOrderShortage[]
}

/** 替代料候选（C49），编码/优先级/比例来自 BOM substitutes。 */
export interface MrpSubstitute {
  materialCode: string
  /** 数值越小越优先。 */
  priority?: number | null
  /** 替代比例原样输出，如 `1:1`。 */
  ratio?: string | null
  /** 替代料可用量 = max(0, 现有库存 + 需求时点前在途 - 安全库存)。 */
  availableQty: MrpQty
}

/** 按物料聚合的缺料明细。 */
export interface MrpMaterialShortage {
  materialCode: string
  materialName?: string | null
  requiredQty: MrpQty
  /** 可用量 = onHand + transit - safetyStock，可为负。 */
  availableQty: MrpQty
  shortageQty: MrpQty
  earliestRequiredAt?: string | null
  affectedOrderNos: string[]
  /** includeSuggestions=true 时输出；数值由后端计算，前端只展示。 */
  suggestedPurchaseDate?: string | null
  suggestedQty?: MrpQty | null
  supplierCode?: string | null
  /** 替代料候选，无替代料时为空数组。 */
  substitutes?: MrpSubstitute[] | null
  /** 最早的非取消在途预计到货日；无在途时为 null。 */
  expectedArrival?: string | null
}

/** POST /mrp/analysis 出参。 */
export interface MrpAnalysisResult {
  sandboxId: string
  analyzedAt: string
  status: MrpStatus
  blocking: boolean
  blocked: boolean
  inventoryEnabled: boolean
  orderCount: number
  warnings: string[]
  orders: MrpOrderResult[]
  shortages: MrpMaterialShortage[]
}
