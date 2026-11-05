import dayjs from 'dayjs'

import type {
  MrpMaterialShortage,
  MrpOrderResult,
  MrpStatus,
  MrpSubstitute
} from '@/types/mrp'

export type MrpTagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export interface MrpStatusMeta {
  label: string
  tagType: MrpTagType
  description: string
}

/** 分级展示文案；取值唯一来源是后端 MrpStatus。 */
export const MRP_STATUS_META: Record<MrpStatus, MrpStatusMeta> = {
  FULL: { label: '齐套', tagType: 'success', description: '所有订单物料齐套，可正常排产。' },
  PARTIAL: {
    label: '部分齐套',
    tagType: 'warning',
    description: '部分订单齐套，其余订单存在缺料。'
  },
  SHORTAGE: { label: '缺料', tagType: 'danger', description: '参与分析的订单均存在缺料。' }
}

/** 未识别的分级按 info 展示，避免 Mock/旧契约缺字段时页面崩溃。 */
export function resolveMrpStatusMeta(status?: string | null): MrpStatusMeta {
  if (status && Object.prototype.hasOwnProperty.call(MRP_STATUS_META, status)) {
    return MRP_STATUS_META[status as MrpStatus]
  }
  return {
    label: status || '未知',
    tagType: 'info',
    description: '后端未返回可识别的齐套分级。'
  }
}

/**
 * 数量展示：按 NUMERIC(18,4) 固定四位小数，绝不舍入成整数。
 * 非零但小于 0.00005 的值显示为 `<0.0001`，避免“真实的少量缺料”被显示成 0。
 */
export function formatQty(value?: number | string | null): string {
  if (value === null || value === undefined || value === '') {
    return '无'
  }
  const numeric = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(numeric)) {
    return '无'
  }
  if (numeric !== 0 && Math.abs(numeric) < 0.00005) {
    return numeric > 0 ? '<0.0001' : '>-0.0001'
  }
  return numeric.toFixed(4)
}

/** 日期展示（交期等 date-only 字段）。 */
export function formatDate(value?: string | null): string {
  if (!value) {
    return '无'
  }
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD') : '无'
}

/** 时间展示：只做时区/格式转换，不重新计算 suggestedPurchaseDate 等后端结果。 */
export function formatDateTime(value?: string | null): string {
  if (!value) {
    return '无'
  }
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm') : '无'
}

/** null / 空串统一显示为“无”，不把空值或原始字段直接裸露给业务用户。 */
export function displayOrDash(value?: string | null): string {
  return value && value.trim() ? value : '无'
}

/** 订单号输入：支持中英文逗号、分号、空格与换行分隔，去重并保持顺序。 */
export function parseOrderNos(input: string): string[] {
  const tokens = input
    .split(/[\s,，;；]+/)
    .map((item) => item.trim())
    .filter(Boolean)
  return Array.from(new Set(tokens))
}

/** 订单缺料行，兼容后端返回空列表或旧 Mock 缺字段。 */
export function shortagesOf(order: MrpOrderResult): MrpOrderResult['shortages'] {
  return Array.isArray(order.shortages) ? order.shortages : []
}

/**
 * 订单级齐套三态：优先用 kittingStatus；旧响应没有该字段时按 kitted 兜底，
 * 避免整页退回“齐套/缺料”二态。
 */
export function orderKittingMeta(order: MrpOrderResult): MrpStatusMeta {
  return resolveMrpStatusMeta(order.kittingStatus ?? (order.kitted ? 'FULL' : 'SHORTAGE'))
}

/** 订单缺料项计数：`2/16`；旧响应缺计数时退回 shortages.length。 */
export function orderKittingCount(order: MrpOrderResult): string {
  const shortage = order.shortageItemCount
  const total = order.totalItemCount
  if (typeof shortage === 'number' && typeof total === 'number') {
    return `${shortage}/${total}`
  }
  if (typeof shortage === 'number') {
    return `${shortage}`
  }
  return `${shortagesOf(order).length}`
}

/** 替代料候选，兼容 null/缺字段。 */
export function substitutesOf(shortage: MrpMaterialShortage): MrpSubstitute[] {
  return Array.isArray(shortage.substitutes) ? shortage.substitutes : []
}

/** 预计到货：无在途显示“无”，不显示空白或 null。 */
export function arrivalText(shortage: MrpMaterialShortage): string {
  return shortage.expectedArrival ? formatDate(shortage.expectedArrival) : '无'
}

/**
 * 展示口径估算：按优先级顺序用 1:1 替代料的可用量冲抵缺口。
 * 仅作界面参考；非 1:1 比例不做换算，直接标注“未折算”。
 */
export function remainingAfterSubstitutes(shortage: MrpMaterialShortage): string {
  const substitutes = substitutesOf(shortage)
  const shortageQty = Number(shortage.shortageQty)
  if (!Number.isFinite(shortageQty)) {
    return '无'
  }
  if (substitutes.length === 0) {
    return `仍缺 ${formatQty(shortageQty)}`
  }
  const ordered = [...substitutes].sort(
    (left, right) =>
      (left.priority ?? Number.MAX_SAFE_INTEGER) - (right.priority ?? Number.MAX_SAFE_INTEGER)
  )
  let remaining = shortageQty
  for (const substitute of ordered) {
    const ratio = substitute.ratio?.trim() || '1:1'
    if (ratio !== '1:1') {
      return `未折算（${substitute.materialCode} 比例 ${ratio}）`
    }
    const available = Number(substitute.availableQty)
    if (Number.isFinite(available)) {
      remaining -= Math.max(0, available)
    }
    if (remaining <= 0) {
      return formatQty(0)
    }
  }
  return `仍缺 ${formatQty(Math.max(0, remaining))}`
}

/** 受影响订单号，兼容旧 Mock 缺字段。 */
export function affectedOrdersOf(shortage: MrpMaterialShortage): string[] {
  return Array.isArray(shortage.affectedOrderNos) ? shortage.affectedOrderNos : []
}

/** 简单的 UUID 形态校验，仅拦截明显输错，最终以 40000 响应为准。 */
export function isSandboxId(value: string): boolean {
  return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(value)
}
