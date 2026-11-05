/**
 * 甘特图适配器实现与工厂（M1-FE-06 + M2-FE-02）。
 * - Mock：默认实现，直接调用 src/mocks/gantt-data.ts 生成固定规模数据。
 * - API：映射 GET /api/v1/gantt/resource（C4）与 /gantt/order（C29）。
 * 订单与资源两视图共用同一套任务时间与 C14 日历解析，不复制口径。
 */
import { ApiError } from '@/api/http'
import { adjustTask, getOrderGantt, getResourceGantt, resolveResourceCalendars } from '@/api/gantt'
import { getEnumLabel } from '@/constants/enums'
import type {
  GanttTask,
  OrderGanttData,
  ResourceGanttData,
  ResourceCalendarResolution,
  TaskAdjustRequest,
  TaskAdjustResult,
  TaskLockType
} from '@/types/gantt'
import type {
  GanttAdapter,
  GanttBarModel,
  GanttDataset,
  GanttLoadQuery,
  GanttRowModel
} from './index'

import {
  buildMockCalendarBindings,
  generateOrderGanttData,
  generateResourceGanttData,
  type GanttDatasetKey
} from '@/mocks/gantt-data'

export type GanttDataSource = 'mock' | 'api'

export interface CreateGanttAdapterOptions {
  /** Mock 规模档位，默认 48×8（FE-01 验收档）。 */
  dataset?: GanttDatasetKey
  /** 数据源，默认 mock；真实联调改为 api。 */
  source?: GanttDataSource
  /** 视图模式，默认 resource；订单甘特传 order。 */
  mode?: 'resource' | 'order'
}

/** 契约 C4 响应 → 适配器内部模型。 */
export function mapResourceGanttData(data: ResourceGanttData): GanttDataset {
  let unscheduledTaskCount = 0
  const rows: GanttRowModel[] = data.resources.map((resource) => {
    const bars: GanttBarModel[] = []
    resource.tasks.forEach((task) => {
      if (!task.startTime || !task.endTime) {
        unscheduledTaskCount += 1
        return
      }
      const setupStartTime = task.setupStartTime ?? task.startTime
      const setupEndTime = task.setupEndTime ?? task.startTime
      bars.push({
        taskId: task.id,
        orderNo: task.orderNo,
        label: task.operationName ? `${task.operationName} · ${task.orderNo}` : task.orderNo,
        startTime: task.startTime,
        endTime: task.endTime,
        setupStartTime,
        setupEndTime,
        status: task.status,
        frozen: task.status === 'FROZEN',
        version: task.version,
        operationIndex: task.operationIndex,
        operationName: task.operationName ?? null,
        resourceCode: task.resourceCode ?? resource.resourceCode,
        secondaryResourceCodes: task.secondaryResourceCodes ?? [],
        qty: task.qty ?? null,
        sequenceInResource: task.sequenceInResource ?? null,
        lockType: task.lockType ?? (task.status === 'FROZEN' ? 'FROZEN' : 'NONE'),
        late: task.late ?? false
      })
    })
    return {
      key: resource.resourceCode,
      // 资源名称本身已带短编码（如“高速贴片线 SMT-01”）时不再重复追加完整编码。
      label: resource.resourceName
        ? resource.resourceName.includes(resource.resourceCode.replace(/^RES-/, ''))
          ? resource.resourceName
          : `${resource.resourceName}（${resource.resourceCode}）`
        : resource.resourceCode,
      subLabel: getEnumLabel('resourceType', resource.resourceType),
      resourceType: resource.resourceType,
      bars
    }
  })

  return {
    mode: 'resource',
    range: { from: data.from, to: data.to },
    rows,
    unscheduledTaskCount
  }
}

/** 契约 C29 订单响应 → 适配器内部模型；订单行内按 operationIndex 保留工序链。 */
export function mapOrderGanttData(data: OrderGanttData): GanttDataset {
  let unscheduledTaskCount = 0
  const rows: GanttRowModel[] = data.orders.map((order) => {
    const bars: GanttBarModel[] = []
    const resourceCodes = new Set<string>()
    order.tasks.forEach((task) => {
      if (task.resourceCode) {
        resourceCodes.add(task.resourceCode)
      }
      if (!task.startTime || !task.endTime) {
        unscheduledTaskCount += 1
        return
      }
      const setupStartTime = task.setupStartTime ?? task.startTime
      const setupEndTime = task.setupEndTime ?? task.startTime
      bars.push({
        taskId: task.id,
        orderNo: task.orderNo,
        label: task.operationName ?? `工序 ${task.operationIndex}`,
        startTime: task.startTime,
        endTime: task.endTime,
        setupStartTime,
        setupEndTime,
        status: task.status,
        frozen: task.status === 'FROZEN',
        version: task.version,
        operationIndex: task.operationIndex,
        operationName: task.operationName ?? null,
        resourceCode: task.resourceCode ?? null,
        secondaryResourceCodes: task.secondaryResourceCodes ?? [],
        qty: task.qty ?? null,
        sequenceInResource: task.sequenceInResource ?? null,
        lockType: task.lockType ?? (task.status === 'FROZEN' ? 'FROZEN' : 'NONE'),
        late: task.late ?? false
      })
    })
    return {
      key: order.orderNo,
      label: order.orderNo,
      subLabel: `${order.materialCode} · 交期 ${order.dueDate}`,
      bars,
      dueDate: order.dueDate,
      dueDateLine: order.dueDateLine ?? `${order.dueDate}T23:59:59Z`,
      priority: order.priority,
      freezeStatus: order.freezeStatus,
      orderStatus: order.orderStatus,
      late: order.late ?? false,
      lateMinutes: order.lateMinutes ?? null,
      lateTaskCount: order.lateTaskCount ?? 0,
      resourceCodes: [...resourceCodes]
    }
  })

  return {
    mode: 'order',
    range: { from: data.from, to: data.to },
    rows,
    unscheduledTaskCount
  }
}

function filterByRange(dataset: GanttDataset, query: GanttLoadQuery): GanttDataset {
  const from = Date.parse(query.from)
  const to = Date.parse(query.to)
  if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to) {
    return dataset
  }
  const rows = dataset.rows
    .map((row) => ({
      ...row,
      bars: row.bars.filter(
        (bar) => Date.parse(bar.endTime) > from && Date.parse(bar.startTime) < to
      )
    }))
    .filter((row) => row.bars.length > 0)
  return {
    ...dataset,
    range: { from: new Date(from).toISOString(), to: new Date(to).toISOString() },
    rows
  }
}

/** 汇总资源编码后统一解析 C14 日历（订单/资源视图共用）。 */
function withCalendars(
  dataset: GanttDataset,
  resolution: ResourceCalendarResolution,
  issues: string[]
): GanttDataset {
  return {
    ...dataset,
    calendarBindings: resolution.bindings,
    calendarIssues: [...issues, ...resolution.issues],
    calendarResourceListLoaded: resolution.resourceListLoaded
  }
}

/** Mock 适配器：不经过 HTTP，直接生成契约结构数据；日历绑定使用 C14 结构的 Mock 日历。 */
export function createMockGanttAdapter(datasetKey: GanttDatasetKey = '48x8'): GanttAdapter {
  let loaded: GanttDataset | null = null
  return {
    mode: 'resource',
    async load(query) {
      const data = generateResourceGanttData(datasetKey, query.sandboxId)
      const dataset = filterByRange(mapResourceGanttData(data), query)
      const resolution = buildMockCalendarBindings(dataset.rows.map((row) => row.key))
      loaded = withCalendars(dataset, resolution, [])
      return loaded
    },
    async adjust(taskId, payload) {
      if (!loaded) {
        throw new ApiError(40901, '数据尚未加载，请刷新后重试')
      }
      return applyMockAdjust(loaded, taskId, payload)
    }
  }
}

/** API 适配器：走 src/api/gantt.ts，字段与冻结契约 C4 一致；日历经 C14 二次查询补齐。 */
export function createApiGanttAdapter(): GanttAdapter {
  return {
    mode: 'resource',
    async load(query) {
      const data = await getResourceGantt({
        sandboxId: query.sandboxId,
        from: query.from,
        to: query.to,
        resourceCodes: query.rowKeys
      })
      const dataset = mapResourceGanttData(data)
      const resolution = await resolveResourceCalendars(dataset.rows.map((row) => row.key))
      return withCalendars(dataset, resolution, [
        'C4 /gantt/resource 增量返回 calendarId/calendarBound；缺失时回退 GET /resources + GET /calendars/{id}'
      ])
    },
    adjust: (taskId, payload, idempotencyKey) => adjustTask(taskId, payload, idempotencyKey)
  }
}

/** 订单视图 Mock：任务集合与资源档同源，日历按订单涉及资源解析。 */
export function createMockOrderGanttAdapter(datasetKey: GanttDatasetKey = '48x8'): GanttAdapter {
  let loaded: GanttDataset | null = null
  return {
    mode: 'order',
    async load(query) {
      const data = generateOrderGanttData(datasetKey, query.sandboxId)
      const dataset = filterByRange(mapOrderGanttData(data), query)
      const resourceCodes = [...new Set(dataset.rows.flatMap((row) => row.resourceCodes ?? []))]
      const resolution = buildMockCalendarBindings(resourceCodes)
      loaded = withCalendars(dataset, resolution, [])
      return loaded
    },
    async adjust(taskId, payload) {
      if (!loaded) {
        throw new ApiError(40901, '数据尚未加载，请刷新后重试')
      }
      return applyMockAdjust(loaded, taskId, payload)
    }
  }
}

/** 订单视图 API：GET /gantt/order（C29）+ C14 资源日历。 */
export function createApiOrderGanttAdapter(): GanttAdapter {
  return {
    mode: 'order',
    async load(query) {
      const data = await getOrderGantt({
        sandboxId: query.sandboxId,
        from: query.from,
        to: query.to,
        orderNos: query.rowKeys
      })
      const dataset = mapOrderGanttData(data)
      const resourceCodes = [...new Set(dataset.rows.flatMap((row) => row.resourceCodes ?? []))]
      const resolution = await resolveResourceCalendars(resourceCodes)
      return withCalendars(dataset, resolution, [
        'C29 /gantt/order 不含资源日历；按任务 resourceCode 走 GET /resources + GET /calendars/{id}（C14）'
      ])
    },
    adjust: (taskId, payload, idempotencyKey) => adjustTask(taskId, payload, idempotencyKey)
  }
}

/** Mock 调整：按冻结语义（C22 + C36）模拟错误码，便于无后端写入时演示交互。 */
const MOCK_MIN_ADJUST_ITEMS = 2
const MOCK_MAX_ADJUST_ITEMS = 10
const MOCK_QTY_TOLERANCE = 1e-4
let mockAdjustmentSequence = 5_200

function toMockTask(bar: GanttBarModel, overrides: Partial<GanttTask> = {}): GanttTask {
  return {
    id: bar.taskId,
    orderNo: bar.orderNo,
    operationIndex: bar.operationIndex ?? 0,
    operationName: bar.operationName ?? null,
    resourceCode: bar.resourceCode ?? null,
    secondaryResourceCodes: bar.secondaryResourceCodes ?? [],
    setupStartTime: bar.setupStartTime,
    setupEndTime: bar.setupEndTime,
    startTime: bar.startTime,
    endTime: bar.endTime,
    qty: bar.qty ?? null,
    status: bar.status,
    sequenceInResource: bar.sequenceInResource ?? null,
    version: bar.version,
    lockType: bar.lockType ?? 'NONE',
    late: bar.late ?? false,
    ...overrides
  }
}

function nextMockTaskId(dataset: GanttDataset): number {
  let max = 0
  dataset.rows.forEach((row) =>
    row.bars.forEach((bar) => {
      max = Math.max(max, bar.taskId)
    })
  )
  return max + 1
}

function assertAdjustable(
  row: GanttRowModel,
  bar: GanttBarModel,
  operation: TaskAdjustRequest['operation']
): void {
  if (bar.status === 'IN_PROGRESS' || bar.status === 'COMPLETED') {
    throw new ApiError(40903, '进行中/已完成任务不可调整')
  }
  if (
    operation === 'SPLIT' ||
    operation === 'MERGE' ||
    operation === 'MOVE' ||
    operation === 'LOCK' ||
    operation === 'UNFREEZE'
  ) {
    const taskFrozen =
      bar.status === 'FROZEN' || bar.lockType === 'FROZEN' || bar.frozen
    if (row.freezeStatus === 'FROZEN' || taskFrozen) {
      throw new ApiError(42201, `任务/订单已冻结，${operation} 不可执行`)
    }
  }
}

function applyMockSplit(
  dataset: GanttDataset,
  row: GanttRowModel,
  bar: GanttBarModel,
  payload: TaskAdjustRequest
): TaskAdjustResult {
  if (!payload.reason?.trim()) {
    throw new ApiError(40000, 'SPLIT 必须提供 reason')
  }
  const qty = bar.qty
  if (qty === null || qty === undefined || qty <= 0) {
    throw new ApiError(42200, 'SPLIT 要求源任务 qty 非空且 > 0')
  }
  const quantities = payload.splitQuantities
  if (!quantities || quantities.length === 0) {
    throw new ApiError(40000, 'SPLIT 必须提供 splitQuantities')
  }
  if (quantities.length < MOCK_MIN_ADJUST_ITEMS || quantities.length > MOCK_MAX_ADJUST_ITEMS) {
    throw new ApiError(42200, `splitQuantities 需为 2–10 项: 实际 ${quantities.length} 项`)
  }
  if (quantities.some((value) => !Number.isFinite(value) || value <= 0)) {
    throw new ApiError(42200, `splitQuantities 每项必须 > 0: ${quantities.join(',')}`)
  }
  const total = quantities.reduce((sum, value) => sum + value, 0)
  if (Math.abs(total - qty) > MOCK_QTY_TOLERANCE) {
    throw new ApiError(42200, `splitQuantities 之和必须等于源任务 qty（合计 ${total} / qty ${qty}）`)
  }
  const start = Date.parse(bar.startTime)
  const end = Date.parse(bar.endTime)
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
    throw new ApiError(42200, '源任务时间窗非法，无法按比例切分')
  }
  const segments: Array<{ start: number; end: number }> = []
  let accumulated = 0
  let cursor = start
  quantities.forEach((value, index) => {
    accumulated += value
    const segEnd =
      index === quantities.length - 1
        ? end
        : start + Math.round((end - start) * (accumulated / total))
    if (segEnd <= cursor) {
      throw new ApiError(42200, '按数量切分后出现零长度子任务，请调整 splitQuantities')
    }
    segments.push({ start: cursor, end: segEnd })
    cursor = segEnd
  })

  if (payload.dryRun) {
    return {
      adjustmentId: null,
      applied: true,
      dryRun: true,
      task: toMockTask(bar, {
        qty: quantities[0],
        startTime: new Date(segments[0].start).toISOString(),
        endTime: new Date(segments[0].end).toISOString(),
        status: 'PLANNED',
        version: bar.version
      }),
      conflicts: [],
      createdTaskIds: [],
      removedTaskIds: []
    }
  }

  const baseSequence = bar.sequenceInResource ?? 0
  row.bars.forEach((item) => {
    if ((item.sequenceInResource ?? 0) > baseSequence) {
      item.sequenceInResource = (item.sequenceInResource ?? 0) + quantities.length - 1
    }
  })
  const createdTaskIds: number[] = []
  const firstNewId = nextMockTaskId(dataset)
  quantities.slice(1).forEach((value, index) => {
    const segment = segments[index + 1]
    const created: GanttBarModel = {
      taskId: firstNewId + index,
      orderNo: bar.orderNo,
      label: `${bar.operationName ?? `工序 ${bar.operationIndex ?? 0}`} · 分批 ${index + 2}`,
      startTime: new Date(segment.start).toISOString(),
      endTime: new Date(segment.end).toISOString(),
      status: 'PLANNED',
      frozen: false,
      version: 1,
      operationIndex: bar.operationIndex,
      operationName: bar.operationName ?? null,
      resourceCode: payload.resourceCode ?? bar.resourceCode ?? null,
      secondaryResourceCodes: bar.secondaryResourceCodes ?? [],
      qty: value,
      sequenceInResource: baseSequence + index + 1,
      lockType: 'NONE',
      late: false
    }
    createdTaskIds.push(created.taskId)
    row.bars.push(created)
  })

  bar.qty = quantities[0]
  bar.startTime = new Date(segments[0].start).toISOString()
  bar.endTime = new Date(segments[0].end).toISOString()
  bar.setupEndTime = bar.startTime
  bar.status = 'PLANNED'
  bar.frozen = false
  bar.lockType = 'NONE'
  bar.version += 1
  row.bars.sort(
    (left, right) =>
      (left.sequenceInResource ?? 0) - (right.sequenceInResource ?? 0) ||
      Date.parse(left.startTime) - Date.parse(right.startTime)
  )
  return {
    adjustmentId: (mockAdjustmentSequence += 1),
    applied: true,
    dryRun: false,
    task: toMockTask(bar),
    conflicts: [],
    createdTaskIds,
    removedTaskIds: []
  }
}

function applyMockMerge(
  row: GanttRowModel,
  bar: GanttBarModel,
  payload: TaskAdjustRequest
): TaskAdjustResult {
  if (!payload.reason?.trim()) {
    throw new ApiError(40000, 'MERGE 必须提供 reason')
  }
  const ids = payload.mergeTaskIds
  if (!ids || ids.length === 0) {
    throw new ApiError(40000, 'MERGE 必须提供 mergeTaskIds')
  }
  if (ids.length < MOCK_MIN_ADJUST_ITEMS || ids.length > MOCK_MAX_ADJUST_ITEMS) {
    throw new ApiError(42201, `mergeTaskIds 需为 2–10 项: 实际 ${ids.length} 项`)
  }
  if (new Set(ids).size !== ids.length) {
    throw new ApiError(42201, 'mergeTaskIds 不允许重复')
  }
  if (!ids.includes(bar.taskId)) {
    throw new ApiError(42201, `mergeTaskIds 必须包含路径任务 id: ${bar.taskId}`)
  }
  const participants = ids.map((id) => row.bars.find((item) => item.taskId === id) ?? null)
  if (participants.some((item) => item === null)) {
    throw new ApiError(40400, '合并任务不存在或不在当前窗口')
  }
  const bars = participants as GanttBarModel[]
  if (
    bars.some(
      (item) =>
        item.orderNo !== bar.orderNo ||
        item.operationIndex !== bar.operationIndex ||
        (item.resourceCode ?? null) !== (bar.resourceCode ?? null)
    )
  ) {
    throw new ApiError(42201, 'MERGE 要求同订单、同工序、同资源')
  }
  if (
    bars.some(
      (item) => item.status === 'FROZEN' || item.frozen || item.lockType === 'FROZEN'
    )
  ) {
    throw new ApiError(42201, 'MERGE 不允许包含冻结任务')
  }
  if (bars.some((item) => item.status === 'IN_PROGRESS' || item.status === 'COMPLETED')) {
    throw new ApiError(40903, 'MERGE 不允许包含进行中/已完成任务')
  }
  if (bars.some((item) => item.qty === null || item.qty === undefined || item.qty <= 0)) {
    throw new ApiError(42200, 'MERGE 要求所有参与任务 qty 非空且 > 0')
  }
  const group = row.bars
    .filter(
      (item) =>
        item.operationIndex === bar.operationIndex &&
        (item.resourceCode ?? null) === (bar.resourceCode ?? null)
    )
    .sort(
      (left, right) =>
        (left.sequenceInResource ?? Number.MAX_SAFE_INTEGER) -
          (right.sequenceInResource ?? Number.MAX_SAFE_INTEGER) ||
        Date.parse(left.startTime) - Date.parse(right.startTime)
    )
  const positions = bars.map((item) => group.findIndex((entry) => entry.taskId === item.taskId))
  positions.sort((left, right) => left - right)
  if (positions.some((value, index) => index > 0 && value !== positions[index - 1] + 1)) {
    throw new ApiError(42201, 'MERGE 要求参与任务在资源内连续相邻')
  }

  const qty = bars.reduce((sum, item) => sum + (item.qty ?? 0), 0)
  const startTime = bars
    .map((item) => item.startTime)
    .reduce((earliest, value) => (Date.parse(value) < Date.parse(earliest) ? value : earliest))
  const endTime = bars
    .map((item) => item.endTime)
    .reduce((latest, value) => (Date.parse(value) > Date.parse(latest) ? value : latest))
  const earliest = bars.reduce((left, right) =>
    Date.parse(left.startTime) <= Date.parse(right.startTime) ? left : right
  )
  const mergedTask = toMockTask(bar, {
    qty,
    startTime,
    endTime,
    setupStartTime: earliest.setupStartTime,
    setupEndTime: earliest.setupEndTime,
    version: bar.version + 1
  })

  if (payload.dryRun) {
    return {
      adjustmentId: null,
      applied: true,
      dryRun: true,
      task: mergedTask,
      conflicts: [],
      createdTaskIds: [],
      removedTaskIds: []
    }
  }

  bar.qty = qty
  bar.startTime = startTime
  bar.endTime = endTime
  bar.setupStartTime = earliest.setupStartTime
  bar.setupEndTime = earliest.setupEndTime
  bar.version += 1
  const removedTaskIds = bars
    .filter((item) => item.taskId !== bar.taskId)
    .map((item) => item.taskId)
  const removed = new Set(removedTaskIds)
  row.bars = row.bars.filter((item) => !removed.has(item.taskId))
  return {
    adjustmentId: (mockAdjustmentSequence += 1),
    applied: true,
    dryRun: false,
    task: mergedTask,
    conflicts: [],
    createdTaskIds: [],
    removedTaskIds
  }
}

function applyMockAdjust(
  dataset: GanttDataset,
  taskId: number,
  payload: TaskAdjustRequest
): TaskAdjustResult {
  const row = dataset.rows.find((item) => item.bars.some((bar) => bar.taskId === taskId))
  const bar = row?.bars.find((item) => item.taskId === taskId)
  if (!row || !bar) {
    throw new ApiError(40400, '任务不存在或不在当前窗口')
  }
  if (payload.version !== bar.version) {
    throw new ApiError(40901, '任务版本已过期，请刷新后重试')
  }
  assertAdjustable(row, bar, payload.operation)
  if (payload.operation === 'SPLIT') {
    return applyMockSplit(dataset, row, bar, payload)
  }
  if (payload.operation === 'MERGE') {
    return applyMockMerge(row, bar, payload)
  }

  let nextStatus = bar.status
  let nextLock: TaskLockType = bar.lockType ?? 'NONE'
  let nextStart = bar.startTime
  let nextEnd = bar.endTime
  if (payload.operation === 'MOVE') {
    if (!payload.startTime) {
      throw new ApiError(40000, 'MOVE 必须提供 startTime')
    }
    const start = Date.parse(payload.startTime)
    // 与后端一致：MOVE 按 startTime 平移，保持原加工时长；endTime 仅作显式覆盖。
    const previousDuration = Date.parse(bar.endTime) - Date.parse(bar.startTime)
    const end = payload.endTime ? Date.parse(payload.endTime) : start + previousDuration
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
      throw new ApiError(40000, '时间窗必须满足 endTime > startTime（零长度窗会被后端拒绝）')
    }
    nextStart = payload.startTime
    nextEnd = new Date(end).toISOString()
    if (payload.resourceCode && payload.resourceCode !== bar.resourceCode) {
      bar.resourceCode = payload.resourceCode
    }
  }
  if (payload.operation === 'LOCK') {
    const from = payload.lockFrom ? Date.parse(payload.lockFrom) : Number.NaN
    const to = payload.lockTo ? Date.parse(payload.lockTo) : Number.NaN
    if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from) {
      throw new ApiError(
        40000,
        '锁定时间窗必须是闭区间且 lockTo > lockFrom（零长度窗会被 DB CHECK 拒绝）'
      )
    }
    nextLock = 'TIME_WINDOW'
  }
  if (payload.operation === 'FREEZE') {
    if (!payload.reason?.trim()) {
      throw new ApiError(40000, 'FREEZE 必须提供 reason')
    }
    nextStatus = 'FROZEN'
    nextLock = 'FROZEN'
  }
  if (payload.operation === 'UNFREEZE') {
    nextStatus = 'PLANNED'
    nextLock = 'NONE'
  }

  const nextVersion = payload.dryRun ? bar.version : bar.version + 1
  const task = toMockTask(bar, {
    startTime: nextStart,
    endTime: nextEnd,
    status: nextStatus,
    version: nextVersion,
    lockType: nextLock
  })
  if (!payload.dryRun) {
    bar.startTime = nextStart
    bar.endTime = nextEnd
    bar.status = nextStatus
    bar.frozen = nextStatus === 'FROZEN'
    bar.lockType = nextLock
    bar.version = nextVersion
  }
  return {
    adjustmentId: payload.dryRun ? null : (mockAdjustmentSequence += 1),
    applied: true,
    dryRun: Boolean(payload.dryRun),
    task,
    conflicts: []
  }
}

/** 页面只依赖此工厂；真实接口切换只改 source，不改页面。 */
export function createGanttAdapter(options: CreateGanttAdapterOptions = {}): GanttAdapter {
  const mode = options.mode ?? 'resource'
  if (mode === 'order') {
    return options.source === 'api'
      ? createApiOrderGanttAdapter()
      : createMockOrderGanttAdapter(options.dataset)
  }
  return options.source === 'api'
    ? createApiGanttAdapter()
    : createMockGanttAdapter(options.dataset)
}

/** Mock 数据档位对应的时间窗，供页面发起 load 使用。 */
export function getMockQueryRange(): { from: string; to: string } {
  return {
    from: new Date(Date.UTC(2026, 10, 5)).toISOString(),
    to: new Date(Date.UTC(2026, 10, 12)).toISOString()
  }
}

export { GANTT_DATASET_PROFILES, getGanttDatasetProfile } from '@/mocks/gantt-data'
export type { GanttDatasetKey }
