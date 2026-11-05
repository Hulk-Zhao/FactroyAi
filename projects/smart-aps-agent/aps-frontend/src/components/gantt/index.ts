/**
 * 甘特图适配器接口（M1-FE-06 PoC 落地）。
 * 约定：页面只依赖本接口，不直接依赖具体绘图库，便于 PoC 与生产实现替换。
 * 目录与归属对齐 docs/api/contract-freeze.md 0.3：FE1 负责 components/gantt。
 */
import type {
  GanttCalendarBinding,
  OrderFreezeStatus,
  OrderPriority,
  TaskAdjustRequest,
  TaskAdjustResult,
  TaskLockType,
  TaskStatus
} from '@/types/gantt'
import type { ResourceType } from '@/types/materials'

export type GanttMode = 'resource' | 'order'

/** 时间轴缩放档位：小时 / 天 / 周 / 月（对齐 PRD FR-GT-001 BR-GT-001-01）。 */
export type GanttZoom = 'hour' | 'day' | 'week' | 'month'

export interface GanttLoadQuery {
  sandboxId?: string
  from: string
  to: string
  rowKeys?: string[]
}

export interface GanttBarModel {
  taskId: number
  orderNo: string
  label: string
  startTime: string
  endTime: string
  /** 契约 C4：setup_seconds=0 时等于 startTime，不允许为空。 */
  setupStartTime?: string
  setupEndTime?: string
  status: TaskStatus
  frozen: boolean
  version: number
  operationIndex?: number
  operationName?: string | null
  resourceCode?: string | null
  /** C4：辅资源编码数组，契约保证为空数组而非 null。 */
  secondaryResourceCodes?: string[]
  qty?: number | null
  sequenceInResource?: number | null
  /** C29：FROZEN 由 status 推导；TIME_WINDOW/RESOURCE 为 V5 锁。 */
  lockType?: TaskLockType
  /** C29：该任务结束时间超过订单交期线。 */
  late?: boolean
}

export interface GanttRowModel {
  key: string
  label: string
  subLabel?: string
  resourceType?: ResourceType
  bars: GanttBarModel[]
  /** 订单视图行元数据（C29），资源视图为空。 */
  dueDate?: string
  dueDateLine?: string
  priority?: OrderPriority
  freezeStatus?: OrderFreezeStatus
  orderStatus?: string
  late?: boolean
  /** 仅 late=true 时有值（分钟），false/null 表示未延期，不得当 0 参与比例。 */
  lateMinutes?: number | null
  lateTaskCount?: number
  /** 订单行涉及的资源编码（去重），用于日历解析与行头展示。 */
  resourceCodes?: string[]
}

export interface GanttDataset {
  mode: GanttMode
  range: { from: string; to: string }
  rows: GanttRowModel[]
  /** 契约允许 startTime/endTime 为空；PoC 跳过未排程任务并记录数量。 */
  unscheduledTaskCount?: number
  /**
   * 资源行 → 工作日历绑定（M2-FE-01 修正）。
   * C4 /gantt/resource 不返回 calendarId，由适配器用 C14（GET /resources + GET /calendars/{id}）补齐；
   * 未绑/缺失/失败必须显式保留状态，页面与热力不得回落到 24h。
   */
  calendarBindings?: GanttCalendarBinding[]
  /** 日历解析缺口（端点/字段级），供页面「数据未接入」标注。 */
  calendarIssues?: string[]
  /** false 表示 GET /resources 整体不可用，全部资源日历无法解析。 */
  calendarResourceListLoaded?: boolean
}

export interface GanttAdapter {
  readonly mode: GanttMode
  load(query: GanttLoadQuery): Promise<GanttDataset>
  adjust?(
    taskId: number,
    payload: TaskAdjustRequest,
    idempotencyKey?: string
  ): Promise<TaskAdjustResult>
}
