/**
 * 产能换算：与 docs/api/contract-freeze.md 第 4 节 C2 公式一一对应。
 * 仅用于前端即时试算/展示，不替代后端工时计算。
 *
 * - UNIT_PER_HOUR：C = cap × eff（件/小时），单件工时 = 3600 / C 秒
 * - SECONDS_PER_UNIT：单件工时 = standard_seconds / eff（秒），cap 不参与
 * - BATCH_SECONDS：n = ceil(q / batch)，每批 = cap / eff 秒
 * - T_total = setup_seconds + T_run，T_run 与 T_total 均向上取整到整秒
 */
import type { CapacityMode } from '@/types/resources'

export interface CapacityInput {
  capacityMode: CapacityMode
  capacityValue?: number | null
  batchCapacity?: number | null
  efficiency?: number | null
  setupSeconds?: number | null
  /** 工序标准工时（秒），仅 SECONDS_PER_UNIT 模式使用。 */
  standardSeconds?: number | null
  qty?: number | null
}

export interface CapacityResult {
  valid: boolean
  message: string
  /** 有效产能（件/小时），仅 UNIT_PER_HOUR 有值。 */
  effectiveCapacityPerHour: number | null
  /** 单件加工工时（秒），BATCH_SECONDS 为平均单件。 */
  perUnitSeconds: number | null
  /** 批次数，仅 BATCH_SECONDS 有值。 */
  batchCount: number | null
  /** 示例数量的加工时长（秒）。 */
  runSeconds: number | null
  /** 加工 + 换型总时长（秒）。 */
  totalSeconds: number | null
}

const EMPTY_RESULT: CapacityResult = {
  valid: false,
  message: '',
  effectiveCapacityPerHour: null,
  perUnitSeconds: null,
  batchCount: null,
  runSeconds: null,
  totalSeconds: null
}

function isValidNumber(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

export function computeCapacity(input: CapacityInput): CapacityResult {
  const qty = isValidNumber(input.qty) && input.qty > 0 ? input.qty : 0
  const eff = isValidNumber(input.efficiency) && input.efficiency > 0 ? input.efficiency : 1
  const batch =
    isValidNumber(input.batchCapacity) && input.batchCapacity > 0 ? input.batchCapacity : 1
  const setup = isValidNumber(input.setupSeconds) && input.setupSeconds > 0 ? input.setupSeconds : 0
  const cap = isValidNumber(input.capacityValue) ? input.capacityValue : 0

  switch (input.capacityMode) {
    case 'UNIT_PER_HOUR': {
      if (cap <= 0) {
        return {
          ...EMPTY_RESULT,
          message: '件/小时模式下 capacity_value 必须大于 0（C2 会返回 42200）'
        }
      }
      const perHour = cap * eff
      if (perHour <= 0) {
        return { ...EMPTY_RESULT, message: '有效产能 cap × eff 必须大于 0（C2 会返回 42200）' }
      }
      const perUnit = 3600 / perHour
      const run = qty > 0 ? Math.ceil((qty * 3600) / perHour) : null
      return {
        valid: true,
        message: '',
        effectiveCapacityPerHour: perHour,
        perUnitSeconds: perUnit,
        batchCount: null,
        runSeconds: run,
        totalSeconds: run === null ? null : Math.ceil(setup + run)
      }
    }
    case 'SECONDS_PER_UNIT': {
      const standard = isValidNumber(input.standardSeconds) ? input.standardSeconds : 0
      if (standard <= 0) {
        return {
          ...EMPTY_RESULT,
          message: '秒/件模式需填入工序标准工时（standard_seconds）才能换算，且必须大于 0'
        }
      }
      const perUnit = standard / eff
      const run = qty > 0 ? Math.ceil(qty * perUnit) : null
      return {
        valid: true,
        message: '',
        effectiveCapacityPerHour: null,
        perUnitSeconds: perUnit,
        batchCount: null,
        runSeconds: run,
        totalSeconds: run === null ? null : Math.ceil(setup + run)
      }
    }
    case 'BATCH_SECONDS': {
      if (cap <= 0) {
        return { ...EMPTY_RESULT, message: '批次秒模式下 capacity_value（每批秒数）必须大于 0' }
      }
      if (!isValidNumber(input.batchCapacity) || input.batchCapacity <= 0) {
        return {
          ...EMPTY_RESULT,
          message: '批次秒模式下 batch_capacity 必须大于 0（不可按 1 兜底）'
        }
      }
      const perBatch = cap / eff
      const batchCount = qty > 0 ? Math.ceil(qty / batch) : null
      const run = batchCount === null ? null : Math.ceil(batchCount * perBatch)
      return {
        valid: true,
        message: '',
        effectiveCapacityPerHour: null,
        perUnitSeconds: perBatch / batch,
        batchCount,
        runSeconds: run,
        totalSeconds: run === null ? null : Math.ceil(setup + run)
      }
    }
    default:
      return { ...EMPTY_RESULT, message: '未知产能模式' }
  }
}

/** 秒数转可读文案：小于 60 秒只显示秒，否则并列分钟。 */
export function formatSeconds(seconds: number | null): string {
  if (seconds === null) {
    return '—'
  }
  if (seconds < 60) {
    return `${seconds.toFixed(0)} 秒`
  }
  return `${(seconds / 60).toFixed(2)} 分钟（${seconds.toFixed(0)} 秒）`
}
