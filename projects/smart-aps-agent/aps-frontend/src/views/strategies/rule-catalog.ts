/**
 * 策略规则字典：规则代码来源 PRD FR-SS-002（MVP 8 条核心规则），
 * 初排维度来源 BR-SS-001-01；取值一旦下发即为契约，前端不得自行扩展。
 */
import type { InitialDimensionRule, InitialSortDimension, StrategyRule } from '@/types/orders'

export interface StrategyRuleCatalogItem {
  code: string
  name: string
  type: 'SOFT' | 'HARD'
  description: string
}

export const STRATEGY_RULE_CATALOG: readonly StrategyRuleCatalogItem[] = [
  { code: 'MIN_TARDINESS', name: '最小超期', type: 'SOFT', description: '减少总延期时间' },
  {
    code: 'MIN_TARDY_COUNT',
    name: '最少超期订单数',
    type: 'SOFT',
    description: '减少延期订单数量'
  },
  { code: 'SPT', name: '短任务优先', type: 'SOFT', description: 'Shortest Processing Time' },
  { code: 'EDD', name: '交期优先', type: 'SOFT', description: 'Earliest Due Date' },
  {
    code: 'SAME_PRODUCT_CONTINUOUS',
    name: '同品连续生产',
    type: 'SOFT',
    description: '减少换型次数'
  },
  { code: 'MIN_SETUP_TIME', name: '最小换型时长', type: 'SOFT', description: '总换型时间最小化' },
  {
    code: 'BALANCE_TASK_COUNT',
    name: '均衡任务数量',
    type: 'SOFT',
    description: '各资源任务数均衡'
  },
  { code: 'BALANCE_LOAD', name: '均衡负载率', type: 'SOFT', description: '各资源负载率均衡' },
  {
    code: 'PRIORITY_RESPECT',
    name: '尊重订单优先级',
    type: 'SOFT',
    description: '高优先级订单优先排入'
  },
  { code: 'MIN_MAKESPAN', name: '最小总完工时间', type: 'SOFT', description: 'Makespan 最小化' }
]

/** MVP 8 条核心规则（PRD 6.1 FR-SS-002），默认权重等分 100。 */
export const MVP_SOFT_RULE_CODES = [
  'MIN_TARDINESS',
  'EDD',
  'SPT',
  'SAME_PRODUCT_CONTINUOUS',
  'MIN_SETUP_TIME',
  'BALANCE_LOAD',
  'PRIORITY_RESPECT',
  'MIN_MAKESPAN'
] as const

export const SOFT_WEIGHT_TOTAL = 100

export const INITIAL_DIMENSION_CATALOG: readonly {
  dimension: InitialSortDimension
  label: string
  defaultDirection: 'ASC' | 'DESC'
}[] = [
  { dimension: 'PRIORITY', label: '优先级', defaultDirection: 'DESC' },
  { dimension: 'DUE_DATE', label: '交期', defaultDirection: 'ASC' },
  { dimension: 'CUSTOMER_LEVEL', label: '客户等级', defaultDirection: 'DESC' },
  { dimension: 'INVENTORY_STATUS', label: '库存状态', defaultDirection: 'ASC' },
  { dimension: 'RELEASE_TIME', label: '下达时间', defaultDirection: 'ASC' }
]

export function findRuleCatalogItem(code: string): StrategyRuleCatalogItem | undefined {
  return STRATEGY_RULE_CATALOG.find((item) => item.code === code)
}

/** 默认 8 条软约束规则：权重合计 100，可直接保存。 */
export function buildDefaultStrategyRules(): StrategyRule[] {
  const weight = SOFT_WEIGHT_TOTAL / MVP_SOFT_RULE_CODES.length
  return MVP_SOFT_RULE_CODES.map((code, index) => {
    const item = findRuleCatalogItem(code)
    return {
      code,
      name: item?.name ?? code,
      phase: 'OPTIMIZATION',
      type: 'SOFT',
      weight,
      params: {},
      enabled: true,
      seqNo: index + 1
    }
  })
}

/** 初排维度默认权重（合计 100；BR-SS-001-04 要求权重和不得为 0）。 */
export function buildDefaultInitialDimensions(): InitialDimensionRule[] {
  const weights = [40, 30, 15, 10, 5]
  return INITIAL_DIMENSION_CATALOG.map((item, index) => ({
    dimension: item.dimension,
    direction: item.defaultDirection,
    weight: weights[index] ?? 0
  }))
}

/** 软约束权重合计（仅统计已启用的 SOFT 规则）。 */
export function sumEnabledSoftWeights(rules: StrategyRule[]): number {
  return rules
    .filter((rule) => rule.enabled && rule.type === 'SOFT')
    .reduce((total, rule) => total + (Number(rule.weight) || 0), 0)
}
