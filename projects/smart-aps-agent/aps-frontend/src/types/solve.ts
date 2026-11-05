/**
 * solve 域类型，字段对齐 docs/api/openapi/solve.yaml。
 * 注意：提交求解的 engine 契约为小写 timefold/ortools/rule_based；
 * solve_task.engine 的库内 CHECK 取值为 TIMEFOLD/ORTOOLS/RULE_BASED（schema.md 4.1）。
 */

import type { SandboxCompare } from '@/types/orders'

export type SolverEngine = 'timefold' | 'ortools' | 'rule_based'

export interface SolveSubmitRequest {
  taskNo?: string
  sandboxId?: string
  strategyId?: string | null
  engine?: SolverEngine
  orderNos?: string[]
  parameters?: Record<string, unknown>
}

export type SolveTaskStatus = 'PENDING' | 'RUNNING' | 'SOLVED' | 'FAILED' | 'CANCELLED' | 'TIMEOUT'

export interface SolveTaskDetail {
  id: number
  taskNo: string
  sandboxId: string
  strategyId?: string | null
  engine: SolverEngine
  status: SolveTaskStatus
  scoreHard?: number | null
  scoreSoft?: number | null
  /** C20：0~1 的求解进度，GET 为事实源。 */
  progress?: number | null
  phase?: string | null
  bestScore?: string | null
  elapsedMs?: number | null
  resultTaskId?: number | null
  startedAt?: string | null
  finishedAt?: string | null
  metrics?: Record<string, unknown>
  errorMessage?: string | null
  createdAt?: string
}

/** C20 STOMP 进度消息体（/topic/solve-tasks/{id}）。 */
export interface SolveProgress {
  taskId: number
  status: SolveTaskStatus
  progress: number
  phase?: string | null
  bestScore?: string | null
  elapsedMs?: number | null
  resultTaskId?: number | null
  errorCode?: string | null
  message?: string | null
}

export type AgentEventType =
  'EQUIPMENT_DOWN' | 'MATERIAL_SHORTAGE' | 'ORDER_RUSH' | 'CAPACITY_ABNORMAL'

export interface AgentEventRequest {
  eventType: AgentEventType
  orderNo?: string | null
  resourceCode?: string | null
  occurredAt: string
  payload?: Record<string, unknown>
}

export interface NlToDslRequest {
  text: string
  sandboxId?: string | null
  context?: Record<string, unknown>
}

export interface NlToDslResult {
  dsl: Record<string, unknown>
  confidence: number
  warnings: string[]
}

export interface ExplainCause {
  type: string
  description: string
  evidence?: Record<string, unknown>
}

/** 约束归因：约束名 + 扣分 + 说明，全部来自求解证据，不由前端拼装。 */
export interface ExplainConstraintImpact {
  justificationId?: string
  constraintId?: string
  constraintName?: string | null
  scoreImpact?: string | null
  hard?: boolean
  weight?: number | null
  deduction?: number | null
  affectedTaskIds?: number[]
  description?: string
}

export interface ExplainLlmInfo {
  configured: boolean
  provider?: string | null
  fallbackReason?: string | null
  fallbackSource?: 'EVIDENCE' | 'LLM'
}

/** C45 `GET /agent/explain/{taskId}`；available=false 时不得编造原因。 */
export interface ExplainResult {
  taskId: number
  available?: boolean
  source?: 'EVIDENCE' | 'LLM'
  noDataReason?: 'NO_JUSTIFICATION_RECORD' | 'AMBIGUOUS_SOLVE_TASK' | null
  summary: string
  narrative?: string
  causes: ExplainCause[]
  suggestions: string[]
  constraintImpacts?: ExplainConstraintImpact[]
  llm?: ExplainLlmInfo
}

export interface WhatIfRequest {
  sandboxId: string
  question: string
  parameters?: Record<string, unknown>
}

export interface WhatIfBottleneckChange {
  before?: { resourceCode: string; utilization: number } | null
  after?: { resourceCode: string; utilization: number } | null
  comparable?: boolean
  notComparableReason?: string | null
  changed?: boolean
  metric?: string
  resourceDeltas?: Array<{ resourceCode: string; before: number; after: number; delta: number }>
}

/** C45 `POST /agent/whatif`：只接受 WHAT_IF 场景，返回对比摘要 + 确定性 answer。 */
export interface WhatIfResult {
  sandboxId?: string
  baselineSandboxId?: string
  baselineTaskCount?: number
  afterTaskCount?: number
  baselineEmpty?: boolean
  comparisonWarnings?: string[]
  question?: string
  appliedChanges?: Array<{ parameter: string; value: unknown }>
  available?: boolean
  unavailableReason?: string | null
  comparison?: SandboxCompare
  impactedOrders?: string[]
  impactedOrdersComplete?: boolean
  bottleneckChange?: WhatIfBottleneckChange
  answer: string
  source?: 'EVIDENCE' | 'LLM'
  solveStatus?: string
}
