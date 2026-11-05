/**
 * gantt 域类型，字段对齐 docs/api/openapi/gantt.yaml；
 * task.status 取值对齐 docs/db/schema.md 第 4.1 节 CHECK 取值。
 */

import type { Calendar } from './calendars'
import type { ResourceType } from './materials'
import type { OrderPriority } from './orders'

export type { OrderPriority } from './orders'

export type TaskStatus = 'PLANNED' | 'FROZEN' | 'IN_PROGRESS' | 'COMPLETED'
export type TaskLockType = 'NONE' | 'TIME_WINDOW' | 'RESOURCE' | 'FROZEN'
export type OrderFreezeStatus = 'UNFROZEN' | 'FROZEN' | 'LOCKED_TIME_WINDOW' | 'LOCKED_RESOURCE'

export interface GanttTask {
  id: number
  orderNo: string
  operationIndex: number
  operationName?: string | null
  resourceCode?: string | null
  secondaryResourceCodes?: string[]
  startTime?: string | null
  endTime?: string | null
  /** 契约 C4：setup_seconds=0 时等于 startTime，不允许为空。 */
  setupStartTime?: string
  setupEndTime?: string
  qty?: number | null
  status: TaskStatus
  sequenceInResource?: number | null
  version: number
  /** C29：M2 由 status 推导 FROZEN|NONE，V5 后可扩展 TIME_WINDOW|RESOURCE。 */
  lockType?: TaskLockType
  /** C29：任务 endTime 是否超过订单 dueDate 当日 UTC 23:59:59。 */
  late?: boolean
}

export interface GanttResourceRow {
  resourceCode: string
  resourceName: string
  resourceType: ResourceType
  tasks: GanttTask[]
}

export interface GanttOrderRow {
  orderId?: string
  orderNo: string
  materialCode: string
  /** 后端 C50 增量字段：产品物料名称；物料缺失/已软删除时为 null，不得用 materialCode 冒充。 */
  materialName?: string | null
  /** 后端 C50 增量字段：产品物料规格；物料缺失/已软删除或规格为空时为 null。 */
  materialSpec?: string | null
  dueDate: string
  /** 后端 C29 增量字段：交期线时间点（dueDate 当日 UTC 23:59:59）；缺失时前端按 dueDate 推导。 */
  dueDateLine?: string
  priority?: OrderPriority
  freezeStatus?: OrderFreezeStatus
  orderStatus?: string
  late?: boolean
  /** 仅 late=true 时给出，整数分钟；late=false 时为 null/缺失，不得当 0 使用。 */
  lateMinutes?: number | null
  lateTaskCount?: number
  tasks: GanttTask[]
}

export interface GanttRangeQuery {
  /** 为空时后端取默认 BASELINE（contract-freeze C5）。 */
  sandboxId?: string
  from: string
  to: string
}

export interface ResourceGanttQuery extends GanttRangeQuery {
  resourceCodes?: string[]
}

export interface OrderGanttQuery extends GanttRangeQuery {
  orderNos?: string[]
}

export interface ResourceGanttData {
  sandboxId: string
  from: string
  to: string
  resources: GanttResourceRow[]
}

export interface OrderGanttData {
  sandboxId: string
  from: string
  to: string
  orders: GanttOrderRow[]
}

export interface TaskAdjustRequest {
  /** C22：MOVE/LOCK/FREEZE/UNFREEZE；C36：SPLIT/MERGE。 */
  operation: 'MOVE' | 'LOCK' | 'FREEZE' | 'UNFREEZE' | 'SPLIT' | 'MERGE'
  version: number
  resourceCode?: string | null
  secondaryResourceCodes?: string[]
  startTime?: string
  endTime?: string
  lockFrom?: string | null
  lockTo?: string | null
  lockedResourceCodes?: string[]
  /** C36 SPLIT：2–10 段，每段 > 0，和 = 源任务 qty（容差 1e-4）。 */
  splitQuantities?: number[]
  /** C36 MERGE：2–10 项，必须包含路径任务自身。 */
  mergeTaskIds?: number[]
  /** FREEZE / SPLIT / MERGE 必填，≤512。 */
  reason?: string
  dryRun?: boolean
  /** M1 兼容别名，等价 operation=FREEZE/UNFREEZE。 */
  frozen?: boolean
}

export interface TaskAdjustConflict {
  type: string
  message: string
  relatedTaskIds: number[]
}

/** C22/C36 响应 TaskAdjustResult；dryRun 返回 applied=true 但不产生 adjustment 行。 */
export interface TaskAdjustResult {
  /** task_adjustment.id BIGSERIAL → int64；dryRun 为 null。 */
  adjustmentId: number | null
  applied: boolean
  dryRun: boolean
  /** 操作后的主任务（SPLIT=源任务第 1 段，MERGE=目标任务）。 */
  task: GanttTask | null
  conflicts: TaskAdjustConflict[]
  /** C36 SPLIT：本次新建任务 id；dryRun=true 时为空。 */
  createdTaskIds?: number[]
  /** C36 MERGE：本次软删任务 id；dryRun=true 时为空。 */
  removedTaskIds?: number[]
}

export interface PlanAction {
  planId: string
  version: number
  publishedAt: string
  affectedTaskCount: number
}

/**
 * 甘特资源行与工作日历的绑定结果（M2-FE-01 修正）。
 * C4 的 /gantt/resource 不返回 calendarId，需用 GET /resources 的 calendarId + GET /calendars/{id}
 * 在前端补齐；任一环节缺失时必须显式落为 UNBOUND/MISSING/ERROR，不得静默按 24h 回落。
 */
export type GanttCalendarBindingState = 'BOUND' | 'UNBOUND' | 'MISSING' | 'ERROR'

export interface GanttCalendarBinding {
  resourceCode: string
  calendarId: string | null
  state: GanttCalendarBindingState
  calendar: Calendar | null
  /** 面向页面的可读说明：未绑、资源缺失、日历缺失、加载失败原因。 */
  message: string
}

export interface ResourceCalendarResolution {
  bindings: GanttCalendarBinding[]
  /** 去重后的接入缺口（端点/字段级），供页面与报告引用。 */
  issues: string[]
  /** false 表示 GET /resources 整体不可用，所有资源均无法解析日历。 */
  resourceListLoaded: boolean
}
