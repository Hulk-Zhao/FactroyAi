/**
 * 策略导入导出类型。
 * 契约来源：docs/api/contract-freeze.md C19、docs/api/openapi/orders.yaml
 * （StrategyExportPackage / StrategyImportRequest / StrategyImportIssue / StrategyImportResult）。
 */
import type { StrategyDirection } from './orders'

/** 导出载荷中的策略头；字段集与 W2 StrategyService#strategyNodeOf 对齐。 */
export interface StrategyExportStrategy {
  id?: string | null
  formatVersion: string
  name: string
  direction: StrategyDirection
  strategyVersion: number
  effectiveFrom?: string | null
  effectiveTo?: string | null
  initialRules?: Record<string, unknown> | null
  applyCondition?: Record<string, unknown> | null
  version?: number | null
}

/** 导入/导出规则条目：C19 规则数组，字段为 code/weight/params/enabled。 */
export interface StrategyRulePayload {
  code: string
  weight: number
  params?: Record<string, unknown> | null
  enabled?: boolean | null
}

/** `GET /strategies/{id}/export` 的 data；checksum 由后端计算，前端不得自行拼装。 */
export interface StrategyExportPackage {
  strategy: StrategyExportStrategy
  rules: StrategyRulePayload[]
  /** 规范化 {strategy, rules} 的 SHA-256，64 位小写十六进制。 */
  checksum: string
}

/** `POST /strategies/import` 请求体：payload 即导出响应的 data。 */
export interface StrategyImportRequest {
  payload: StrategyExportPackage
  /** true 只校验不落库；缺省 false。 */
  dryRun?: boolean
}

/**
 * 导入校验错误项（仅出现在 HTTP 200 + data.valid=false 的字段级校验失败中）。
 * code 为后端契约的 string 型 HTTP 层码投影（如 "42200"），不是字段级规则码。
 */
export interface StrategyImportIssue {
  field: string
  code: string
  message: string
}

/** `POST /strategies/import` 的 data；existing 为 W2 收口新增的幂等兼容字段。 */
export interface StrategyImportResult {
  valid: boolean
  errors: StrategyImportIssue[]
  /** 仅 dryRun=false 且落库成功（或幂等命中）时给出。 */
  strategyId?: string | null
  /** true 表示同名同版本且内容一致，本次不新建。 */
  existing?: boolean
}
