/**
 * orders 域类型，字段对齐 docs/api/openapi/orders.yaml；
 * 枚举取值对齐 docs/db/schema.md 第 4.1 节 CHECK 取值。
 */

export type OrderSource = 'ERP_SYNC' | 'MANUAL' | 'AGENT'

export type OrderPriority = 'URGENT' | 'HIGH' | 'NORMAL' | 'LOW'

export type OrderStatus =
  'DRAFT' | 'CONFIRMED' | 'SCHEDULED' | 'RELEASED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'

export type FreezeStatus = 'UNFROZEN' | 'FROZEN' | 'LOCKED_TIME_WINDOW' | 'LOCKED_RESOURCE'

export interface Order {
  orderNo: string
  materialCode: string
  qty: number
  status: OrderStatus
  freezeStatus: FreezeStatus
  dueDate: string
  priority: OrderPriority
  source: OrderSource
  /** 客户业务名称；后端补齐前页面不展示裸 UUID。 */
  customerName?: string | null
  /** 客户编码，作为客户名称的副文本展示。 */
  customerCode?: string | null
  customerId?: string | null
  routingId?: string | null
  remark?: string | null
  extAttrs?: Record<string, unknown>
  version: number
  createdAt?: string
  updatedAt?: string
}

export interface OrderCreateRequest {
  orderNo: string
  source?: OrderSource
  materialCode: string
  qty: number
  dueDate: string
  priority?: OrderPriority
  customerId?: string | null
  routingId?: string | null
  remark?: string
  extAttrs?: Record<string, unknown>
}

/** 更新请求：仅 DRAFT 可编辑，version 用于乐观锁。 */
export interface OrderUpdateRequest {
  materialCode: string
  qty: number
  dueDate: string
  priority: OrderPriority
  customerId?: string | null
  routingId?: string | null
  remark?: string | null
  extAttrs?: Record<string, unknown>
  version: number
}

/** 列表筛选：全部为可选参数，空值由序列化层跳过。 */
export interface OrderQuery {
  orderNo?: string
  customerId?: string
  materialCode?: string
  status?: OrderStatus
  dueDateFrom?: string
  dueDateTo?: string
}

export interface OrderFreezeRequest {
  freezeStatus: FreezeStatus
  version: number
}

export type AtpMode = 'FAST' | 'ACCURATE'
export type AtpStatus = 'OK' | 'INFEASIBLE' | 'TIMEOUT' | 'FAILED'
/** FAST 恒为 ESTIMATE；ACCURATE 仅完整可行解为 VERIFIED，超时/降级为 UNVERIFIED。 */
export type AtpAccuracy = 'ESTIMATE' | 'VERIFIED' | 'UNVERIFIED'

export interface AtpRequest {
  materialCode: string
  qty: number
  requestedDate: string
  customerId?: string | null
  /** 可选；存在时必须为 CONFIRMED/SCHEDULED/RELEASED/IN_PROGRESS 且与物料一致，否则 422/42200。 */
  orderNo?: string | null
  mode?: AtpMode
  timeoutSeconds?: number
  allowSplitBatch?: boolean
}

export interface AtpBottleneck {
  type: string
  resourceCode?: string | null
  operationCode?: string | null
  operationIndex?: number | null
  delayMinutes: number
  detail: string
}

export interface AtpSuggestion {
  action: string
  resourceCode?: string | null
  operationCode?: string | null
  operationIndex?: number | null
  expectedGainMinutes: number
  detail: string
}

/** 建议性标识：advisoryOnly 恒为 true，capacity lock 不写主计划。 */
export interface AtpCapacityLockSuggestion {
  advisoryOnly: boolean
  required?: boolean
  reason?: string
  resourceCodes?: string[]
  from?: string | null
  to?: string | null
}

export interface AtpSolveInfo {
  engine?: string
  timeoutSeconds?: number | null
  elapsedMs?: number
  timedOut?: boolean
  solverStatus?: string | null
  scoreHard?: number | null
  scoreSoft?: number | null
  assignedTaskCount?: number
  unassignedTaskCount?: number
  message?: string
}

/** C46 响应；mainPlanWritten 恒为 false，TIMEOUT/INFEASIBLE 时不含任何日期字段。 */
export interface AtpResult {
  materialCode: string
  qty: number
  requestedDate: string
  orderNo?: string | null
  mode?: AtpMode
  accuracy?: AtpAccuracy
  status?: AtpStatus
  feasible?: boolean | null
  earliestDeliveryDate?: string | null
  /** C41 兼容别名，与 earliestDeliveryDate 同值。 */
  earliestDate?: string | null
  leadTimeDays?: number | null
  bottleneckResourceCode?: string | null
  bottlenecks?: AtpBottleneck[]
  suggestions?: AtpSuggestion[]
  capacityLockSuggestion?: AtpCapacityLockSuggestion
  solve?: AtpSolveInfo
  assumptions?: string[]
  mainPlanWritten?: boolean
  message?: string
}

export type StrategyDirection = 'FORWARD' | 'BACKWARD' | 'BOTTLENECK'

/** 策略规则类型，对齐 strategy_rule.rule_type CHECK（HARD/SOFT）。 */
export type StrategyRuleType = 'HARD' | 'SOFT'

/** 策略规则阶段，对齐 strategy_rule.rule_phase CHECK（INITIAL/OPTIMIZATION）。 */
export type StrategyRulePhase = 'INITIAL' | 'OPTIMIZATION'

/** 策略规则明细，字段对齐 strategy_rule（db/migration/V1__init.sql 708-736）。 */
export interface StrategyRule {
  id?: string
  code: string
  name?: string | null
  phase: StrategyRulePhase
  type: StrategyRuleType
  weight: number
  params?: Record<string, unknown>
  enabled: boolean
  seqNo: number
}

/** 初排维度，对齐 BR-SS-001-01。 */
export type InitialSortDimension =
  'PRIORITY' | 'DUE_DATE' | 'CUSTOMER_LEVEL' | 'INVENTORY_STATUS' | 'RELEASE_TIME'

export type InitialSortDirection = 'ASC' | 'DESC'

/** 初排维度规则，对齐 PRD FR-SS-001 入参 `rules: [{dimension, direction, weight}]`。 */
export interface InitialDimensionRule {
  dimension: InitialSortDimension
  direction: InitialSortDirection
  weight: number
}

export interface Strategy {
  id: string
  name: string
  direction: StrategyDirection
  strategyVersion: number
  effectiveFrom: string
  effectiveTo?: string | null
  initialRules?: Record<string, unknown>
  optimizationRules?: Record<string, unknown>
  applyCondition?: Record<string, unknown>
  /** 结构化规则明细；以 strategy_rule 为准（schema.md 5.8）。 */
  rules?: StrategyRule[]
  version: number
}

export interface StrategyCreateRequest {
  name: string
  direction: StrategyDirection
  strategyVersion?: number
  effectiveFrom: string
  effectiveTo?: string | null
  initialRules?: Record<string, unknown>
  optimizationRules?: Record<string, unknown>
  applyCondition?: Record<string, unknown>
  rules?: StrategyRule[]
}

/** 策略更新：C19 `PUT /strategies/{id}`，rules 为全量替换，version 为乐观锁。 */
export interface StrategyUpdateRequest extends StrategyCreateRequest {
  rules: StrategyRule[]
  version: number
}

export interface StrategyDetail extends Strategy {
  rules: StrategyRule[]
}

export type SandboxType = 'BASELINE' | 'WHAT_IF' | 'ARCHIVED'

export interface Sandbox {
  id: string
  name: string
  type: SandboxType
  parentSandboxId?: string | null
  expiresAt?: string | null
  snapshot?: Record<string, unknown>
  version: number
  createdAt?: string
}

export interface SandboxCreateRequest {
  name: string
  /** C32：创建只允许 WHAT_IF（缺省即 WHAT_IF），BASELINE/ARCHIVED 后端返回 42200。 */
  type?: 'WHAT_IF'
  parentSandboxId?: string | null
  expiresAt?: string | null
}

export type SandboxChangeType = 'ADDED' | 'REMOVED' | 'MOVED'

export interface SandboxChangedTask {
  taskId: number
  orderNo: string
  resourceCode: string
  changeType: SandboxChangeType
}

/** C32 `GET /sandboxes/{id}/compare?targetId=`，方向固定 `id - targetId`。 */
export interface SandboxCompare {
  sandboxId: string
  targetId: string
  /** 交期超期分钟差，可为负（分钟，正=被比较方更晚）。 */
  dueDateDeltaMinutes: number
  /** 0–1 小数，保留 3 位。 */
  utilizationDelta: number
  setupCountDelta: number
  /** 明细被截断时计数字段仍是全量真实值。 */
  addedTaskCount: number
  removedTaskCount: number
  movedTaskCount: number
  changedTasks: SandboxChangedTask[]
  truncated: boolean
}

/** C32 apply 响应中的场景摘要（列表同样不返回 snapshot）。 */
export interface SandboxSummary {
  id: string
  name: string
  type: SandboxType
  parentSandboxId?: string | null
  expiresAt?: string | null
  version: number
  createdAt?: string
}

/** C32 `POST /sandboxes/{id}/apply`：单事务发布新版本并提升 BASELINE。 */
export interface SandboxApplyResult {
  sandbox: SandboxSummary
  plan: import('./plans').PlanAction
}
