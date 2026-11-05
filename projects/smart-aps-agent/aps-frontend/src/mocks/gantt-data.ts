/**
 * 甘特图 Mock 数据生成器（M1-FE-06 PoC + M2-FE-01 增强演示）。
 * 独立文件，不改动 handlers.ts；默认由 components/gantt 的 Mock 适配器直接调用。
 * 三档固定规模：48 任务 × 8 资源（FE-01 验收档）、500 任务 × 50 资源、100 任务 × 10 资源；
 * 同一档位使用固定随机种子，结果可复现。
 */
import { DEFAULT_SANDBOX_ID } from '@/constants/defaults'
import type { Calendar } from '@/types/calendars'
import type {
  GanttCalendarBinding,
  GanttOrderRow,
  GanttResourceRow,
  GanttTask,
  OrderGanttData,
  ResourceCalendarResolution,
  ResourceGanttData,
  TaskStatus
} from '@/types/gantt'
import type { ResourceType } from '@/types/materials'

export type GanttDatasetKey = '48x8' | '500x50' | '100x10' | '5000x50'

export interface GanttDatasetProfile {
  key: GanttDatasetKey
  label: string
  taskCount: number
  resourceCount: number
  seed: number
}

export const GANTT_DATASET_PROFILES: readonly GanttDatasetProfile[] = [
  {
    key: '48x8',
    label: '48 任务 × 8 资源（FE-01 验收档）',
    taskCount: 48,
    resourceCount: 8,
    seed: 20261107
  },
  { key: '500x50', label: '500 任务 × 50 资源', taskCount: 500, resourceCount: 50, seed: 20261105 },
  { key: '100x10', label: '100 任务 × 10 资源', taskCount: 100, resourceCount: 10, seed: 20261106 },
  {
    key: '5000x50',
    label: '5000 任务 × 50 资源（M3-FE-04 容量档）',
    taskCount: 5000,
    resourceCount: 50,
    seed: 20261108
  }
]

const RANGE_START = Date.UTC(2026, 10, 5, 0, 0, 0)
const RANGE_END = Date.UTC(2026, 10, 12, 0, 0, 0)
const MINUTE = 60 * 1000

const OPERATION_NAMES = ['下料', '机加工', '装配', '测试', '包装'] as const

interface ResourceKind {
  type: ResourceType
  prefix: string
  name: string
}

/** 10 个槽位的资源类型分布：主资源 7、辅资源 1、人力 1、工具 1。 */
const RESOURCE_SLOTS: readonly ResourceKind[] = [
  { type: 'PRIMARY', prefix: 'CNC', name: '数控加工中心' },
  { type: 'PRIMARY', prefix: 'CNC', name: '数控加工中心' },
  { type: 'PRIMARY', prefix: 'SMT', name: '高速贴片线' },
  { type: 'PRIMARY', prefix: 'SMT', name: '高速贴片线' },
  { type: 'PRIMARY', prefix: 'PACK', name: 'PACK 总装线' },
  { type: 'PRIMARY', prefix: 'PACK', name: 'PACK 总装线' },
  { type: 'PRIMARY', prefix: 'AGING', name: '老化房' },
  { type: 'SECONDARY', prefix: 'FIX', name: '工装夹具' },
  { type: 'HUMAN', prefix: 'OP', name: '装调技师' },
  { type: 'TOOL', prefix: 'MOLD', name: '模具' }
]

export function getGanttDatasetProfile(key: string): GanttDatasetProfile {
  return GANTT_DATASET_PROFILES.find((profile) => profile.key === key) ?? GANTT_DATASET_PROFILES[0]
}

/** mulberry32：固定种子线性同余，保证同一档位每次生成完全相同的数据。 */
function createRandom(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let value = state
    value = Math.imul(value ^ (value >>> 15), value | 1)
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61)
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296
  }
}

function randomInt(random: () => number, min: number, max: number): number {
  return min + Math.floor(random() * (max - min + 1))
}

function iso(milliseconds: number): string {
  return new Date(milliseconds).toISOString()
}

function statusOf(random: () => number): TaskStatus {
  const value = random()
  if (value < 0.7) {
    return 'PLANNED'
  }
  if (value < 0.82) {
    return 'FROZEN'
  }
  if (value < 0.92) {
    return 'IN_PROGRESS'
  }
  return 'COMPLETED'
}

function buildResources(count: number): Array<{
  row: Omit<GanttResourceRow, 'tasks'>
  kind: ResourceKind
  sequence: number
}> {
  const counters = new Map<string, number>()
  return Array.from({ length: count }, (_, index) => {
    const kind = RESOURCE_SLOTS[index % RESOURCE_SLOTS.length]
    const sequence = (counters.get(kind.prefix) ?? 0) + 1
    counters.set(kind.prefix, sequence)
    const code = `RES-${kind.prefix}-${String(100 + sequence).padStart(3, '0')}`
    return {
      row: {
        resourceCode: code,
        resourceName: `${kind.name} ${kind.prefix}-${String(100 + sequence).padStart(3, '0')}`,
        resourceType: kind.type
      },
      kind,
      sequence
    }
  })
}

/**
 * 生成资源甘特数据（契约 C4 结构）。
 * 48×8 档位走订单工序链场景（含可复现的 1 处资源重叠 + 1 处依赖违例 + 3 个冻结任务）；
 * 其余档位走每资源顺序任务链，用于渲染性能对比。
 */
export function generateResourceGanttData(
  key: GanttDatasetKey,
  sandboxId: string = DEFAULT_SANDBOX_ID
): ResourceGanttData {
  if (key === '48x8') {
    return generateFe01ResourceGanttData(sandboxId)
  }
  return generateDenseResourceGanttData(key, sandboxId)
}

function generateDenseResourceGanttData(
  key: GanttDatasetKey,
  sandboxId: string = DEFAULT_SANDBOX_ID
): ResourceGanttData {
  const profile = getGanttDatasetProfile(key)
  const random = createRandom(profile.seed)
  const resources = buildResources(profile.resourceCount)
  const tasksByResource = new Map<string, GanttTask[]>(
    resources.map((item) => [item.row.resourceCode, []])
  )
  const perResource = Math.floor(profile.taskCount / profile.resourceCount)
  const remainder = profile.taskCount % profile.resourceCount

  let taskIndex = 0
  resources.forEach((resource, resourceIndex) => {
    const bucket = tasksByResource.get(resource.row.resourceCode)
    if (!bucket) {
      return
    }
    const count = perResource + (resourceIndex < remainder ? 1 : 0)
    let cursor = RANGE_START + randomInt(random, 30, 300) * MINUTE
    for (let sequence = 1; sequence <= count; sequence += 1) {
      const setupMinutes = random() < 0.4 ? 0 : randomInt(random, 15, 45)
      const runMinutes = randomInt(random, 30, 180)
      const setupStart = cursor
      const start = setupStart + setupMinutes * MINUTE
      const end = start + runMinutes * MINUTE
      const orderIndex = Math.floor(taskIndex / 3) + 1
      const orderNo = `DM-SO-${String(1001 + orderIndex)}`
      const operationIndex = (taskIndex % 3) + 1
      bucket.push({
        id: 500001 + taskIndex,
        orderNo,
        operationIndex,
        operationName: OPERATION_NAMES[(operationIndex - 1) % OPERATION_NAMES.length],
        resourceCode: resource.row.resourceCode,
        secondaryResourceCodes:
          random() < 0.2 && resource.kind.type === 'PRIMARY' ? ['RES-FIX-101'] : [],
        setupStartTime: iso(setupStart),
        setupEndTime: iso(start),
        startTime: iso(start),
        endTime: iso(end),
        qty: randomInt(random, 20, 500),
        status: statusOf(random),
        sequenceInResource: sequence,
        version: randomInt(random, 1, 3)
      })
      cursor = end + randomInt(random, 30, 600) * MINUTE
      taskIndex += 1
    }
  })

  const rows: GanttResourceRow[] = resources.map((resource) => ({
    ...resource.row,
    tasks: tasksByResource.get(resource.row.resourceCode) ?? []
  }))

  return {
    sandboxId,
    from: iso(RANGE_START),
    to: iso(RANGE_END),
    resources: rows
  }
}

/**
 * 订单视图 Mock（M2-FE-02）：直接复用资源档的任务集合，按 orderNo 分组，
 * 不另造一份任务时间，保证订单甘特与资源甘特的口径同源。
 * dueDateLine 固定为 dueDate 当日 UTC 23:59:59（与 C29 一致）；每 3 个订单制造 1 个延期样本。
 */
export function generateOrderGanttData(
  datasetKey: GanttDatasetKey = '48x8',
  sandboxId: string = DEFAULT_SANDBOX_ID
): OrderGanttData {
  const resourceData = generateResourceGanttData(datasetKey, sandboxId)
  const grouped = new Map<string, GanttTask[]>()
  resourceData.resources.forEach((resource) => {
    resource.tasks.forEach((task) => {
      const bucket = grouped.get(task.orderNo) ?? []
      bucket.push(task)
      grouped.set(task.orderNo, bucket)
    })
  })

  const priorities = ['HIGH', 'NORMAL', 'URGENT', 'LOW'] as const
  const orders: GanttOrderRow[] = [...grouped.entries()]
    .map(([orderNo, tasks], index): GanttOrderRow => {
      tasks.sort((left, right) => left.operationIndex - right.operationIndex)
      const lastEnd = Math.max(...tasks.map((task) => Date.parse(task.endTime ?? '')))
      const makeLate = index % 3 === 1 && index >= 4
      const dueMs = lastEnd + (makeLate ? -1 : 1) * 24 * 60 * MINUTE
      const dueDate = new Date(dueMs).toISOString().slice(0, 10)
      const dueDateLine = `${dueDate}T23:59:59Z`
      const late = lastEnd > Date.parse(dueDateLine)
      const lateMinutes = late ? Math.ceil((lastEnd - Date.parse(dueDateLine)) / MINUTE) : null
      const frozenOrder = index === 3
      const rowTasks = tasks.map((task): GanttTask => {
        const isLate =
          Boolean(task.endTime) && Date.parse(task.endTime ?? '') > Date.parse(dueDateLine)
        return {
          ...task,
          lockType: task.status === 'FROZEN' ? 'FROZEN' : 'NONE',
          late: isLate
        }
      })
      return {
        orderNo,
        materialCode: ['FG-ESS-001', 'SEMI-MOD-001', 'SEMI-SMT-001'][index % 3],
        dueDate,
        dueDateLine,
        priority: priorities[index % priorities.length],
        freezeStatus: frozenOrder ? 'FROZEN' : 'UNFROZEN',
        late,
        lateMinutes,
        lateTaskCount: rowTasks.filter((task) => task.late).length,
        tasks: rowTasks
      }
    })
    .sort((left, right) => left.orderNo.localeCompare(right.orderNo))

  return {
    sandboxId,
    from: resourceData.from,
    to: resourceData.to,
    orders
  }
}
/**
 * Mock 工作日历（结构对齐 C14：班次 + 例外日 + 生效期），仅用于演示停机层与热力分母。
 * 这些对象不属于 C4 响应；真实接口通过 GET /resources 的 calendarId + GET /calendars/{id} 获取。
 */
function mockCalendar(
  id: string,
  name: string,
  effectiveFrom: string,
  effectiveTo: string | null,
  shifts: Calendar['shifts'],
  holidays: NonNullable<Calendar['holidays']> = []
): Calendar {
  return {
    id,
    name,
    calendarVersion: 1,
    type: 'RESOURCE',
    effectiveFrom,
    effectiveTo,
    shifts,
    holidays,
    version: 1
  }
}

const WEEKDAY = [1, 2, 3, 4, 5]
const WEEKDAY_SATURDAY = [1, 2, 3, 4, 5, 6]
const EVERY_DAY = [1, 2, 3, 4, 5, 6, 7]

/** 常白班：08:00-12:00 / 13:00-17:00，周一至周五；含 1 个法定停产日 + 1 个调休上班日；生效期到 11-10。 */
const MOCK_CAL_DAY = mockCalendar(
  'CAL-PLANT-1SHIFT',
  '全厂单班制日历（08:00-17:00）',
  '2026-11-01',
  '2026-11-10',
  [
    { startTime: '08:00', endTime: '12:00', daysOfWeek: WEEKDAY },
    { startTime: '13:00', endTime: '17:00', daysOfWeek: WEEKDAY }
  ],
  [
    { date: '2026-11-06', name: '设备年度保养', kind: 'LEGAL' },
    { date: '2026-11-08', name: '调休补班', kind: 'MAKEUP' }
  ]
)

/** 两班制：08:00-20:00，周一至周六；11-10 公司停产检修。 */
const MOCK_CAL_TWO = mockCalendar(
  'CAL-SMT-2SHIFT',
  'SMT 贴片车间两班制日历',
  '2026-11-01',
  null,
  [{ startTime: '08:00', endTime: '20:00', daysOfWeek: WEEKDAY_SATURDAY }],
  [{ date: '2026-11-10', name: '计划停电检修', kind: 'COMPANY' }]
)

/** 连续生产：00:00-24:00，每天。 */
const MOCK_CAL_CONTINUOUS = mockCalendar(
  'CAL-FAC-7X24',
  '全厂 7×24 连续生产日历',
  '2026-01-01',
  null,
  [{ startTime: '00:00', endTime: '24:00', daysOfWeek: EVERY_DAY }]
)

const MOCK_CALENDARS: Record<string, Calendar> = {
  [MOCK_CAL_DAY.id]: MOCK_CAL_DAY,
  [MOCK_CAL_TWO.id]: MOCK_CAL_TWO,
  [MOCK_CAL_CONTINUOUS.id]: MOCK_CAL_CONTINUOUS
}

/** 48×8 验收档的日历绑定；null 表示资源存在但未绑日历，MISSING 表示资源列表查不到。 */
const FE01_CALENDAR_IDS: Record<string, string | null> = {
  'RES-SMT-01': 'CAL-FAC-7X24',
  'RES-SMT-02': 'CAL-FAC-7X24',
  'RES-PACK-01': 'CAL-FAC-7X24',
  'RES-PACK-02': 'CAL-SMT-2SHIFT',
  'RES-AGING-01': 'CAL-FAC-7X24',
  'RES-AGING-02': 'CAL-SMT-2SHIFT',
  'RES-FIX-102': null
}

const FE01_MISSING_RESOURCES = new Set(['RES-FIX-101'])

const FALLBACK_CALENDAR_ROTATION = ['CAL-PLANT-1SHIFT', 'CAL-SMT-2SHIFT', 'CAL-FAC-7X24']

/**
 * Mock 日历绑定解析：演示 BOUND / UNBOUND / MISSING 三种状态。
 * 真实实现见 api/gantt.ts 的 resolveResourceCalendars（GET /resources + GET /calendars/{id}）。
 */
export function buildMockCalendarBindings(resourceCodes: string[]): ResourceCalendarResolution {
  const issues = new Set<string>([
    'Mock 说明：日历结构对齐 C14（班次 / 例外日 / 生效期），本档数据不经过 HTTP'
  ])
  const bindings: GanttCalendarBinding[] = resourceCodes.map((code, index) => {
    if (FE01_MISSING_RESOURCES.has(code)) {
      issues.add(`Mock 缺口演示：GET /resources 未返回资源 ${code}（C14 资源列表缺失）`)
      return {
        resourceCode: code,
        calendarId: null,
        state: 'MISSING',
        calendar: null,
        message: 'Mock：资源列表未返回该资源编码；停机与热力不可比'
      }
    }
    const hasOwn = Object.prototype.hasOwnProperty.call(FE01_CALENDAR_IDS, code)
    const calendarId = hasOwn
      ? FE01_CALENDAR_IDS[code]
      : FALLBACK_CALENDAR_ROTATION[index % FALLBACK_CALENDAR_ROTATION.length]
    if (!calendarId) {
      issues.add(`Mock 缺口演示：资源 ${code} 未绑定 calendarId（C14 响应字段为空）`)
      return {
        resourceCode: code,
        calendarId: null,
        state: 'UNBOUND',
        calendar: null,
        message: 'Mock：资源未绑定工作日历；停机与热力不可比'
      }
    }
    const calendar = MOCK_CALENDARS[calendarId]
    if (!calendar) {
      issues.add(`Mock 缺口演示：GET /calendars/{id} 未返回日历 ${calendarId}`)
      return {
        resourceCode: code,
        calendarId,
        state: 'MISSING',
        calendar: null,
        message: 'Mock：日历详情缺失；停机与热力不可比'
      }
    }
    return {
      resourceCode: code,
      calendarId,
      state: 'BOUND',
      calendar,
      message: `Mock：已绑定日历 ${calendar.name}`
    }
  })
  return { bindings, issues: [...issues], resourceListLoaded: true }
}

interface Fe01Resource {
  code: string
  name: string
  type: ResourceType
}
const FE01_RESOURCES: readonly Fe01Resource[] = [
  { code: 'RES-SMT-01', name: '高速贴片线 SMT-01', type: 'PRIMARY' },
  { code: 'RES-SMT-02', name: '高速贴片线 SMT-02', type: 'PRIMARY' },
  { code: 'RES-PACK-01', name: 'PACK 总装线 PACK-01', type: 'PRIMARY' },
  { code: 'RES-PACK-02', name: 'PACK 总装线 PACK-02', type: 'PRIMARY' },
  { code: 'RES-AGING-01', name: '老化房 AGING-01（20 工位）', type: 'PRIMARY' },
  { code: 'RES-AGING-02', name: '老化房 AGING-02（20 工位）', type: 'PRIMARY' },
  { code: 'RES-FIX-101', name: '工装夹具 FIX-101', type: 'SECONDARY' },
  { code: 'RES-FIX-102', name: '工装夹具 FIX-102', type: 'SECONDARY' }
]

const FE01_OPERATIONS = ['SMT 贴片', 'PACK 总装', '老化测试', '夹具校验'] as const
const FE01_OPERATION_RESOURCES: readonly [number, number, number, number] = [0, 2, 4, 6]

function resourceOf(baseIndex: number, pair: 1 | 2): Fe01Resource {
  return FE01_RESOURCES[baseIndex + (pair === 1 ? 0 : 1)]
}

/**
 * 48 任务 × 8 资源验收档：12 个订单 × 4 道工序，订单内工序链跨资源。
 * 12 个订单按 12 小时错峰开工，链长约 9–11 小时，正常情况下互不重叠。
 * 两处可复现异常：
 * - 订单 DM-SO-1005 的首道工序提前到订单 DM-SO-1003 首道工序开始后 30 分钟 → 同资源重叠冲突；
 * - 订单 DM-SO-1002 的第二道工序提前到首道工序结束前 45 分钟 → 工序依赖违例。
 */
function generateFe01ResourceGanttData(sandboxId: string): ResourceGanttData {
  const profile = getGanttDatasetProfile('48x8')
  const random = createRandom(profile.seed)
  const tasks: GanttTask[] = []
  const orderCount = 12
  const orderStart = RANGE_START + 30 * MINUTE

  for (let orderIndex = 0; orderIndex < orderCount; orderIndex += 1) {
    const pair: 1 | 2 = orderIndex % 2 === 0 ? 1 : 2
    const orderNo = `DM-SO-${String(1001 + orderIndex)}`
    let cursor = orderStart + orderIndex * 12 * 60 * MINUTE
    for (let operation = 1; operation <= FE01_OPERATIONS.length; operation += 1) {
      const resource = resourceOf(FE01_OPERATION_RESOURCES[operation - 1], pair)
      const setupMinutes = random() < 0.5 ? 0 : randomInt(random, 20, 40)
      const runMinutes = randomInt(random, 60, 120)
      const setupStart = cursor
      const start = setupStart + setupMinutes * MINUTE
      const end = start + runMinutes * MINUTE
      const isCompletedOrder = orderIndex === 0
      const status: TaskStatus = isCompletedOrder
        ? 'COMPLETED'
        : (orderIndex === 2 && operation === 3) ||
            (orderIndex === 7 && operation === 2) ||
            (orderIndex === 10 && operation === 1)
          ? 'FROZEN'
          : orderIndex === 1 && operation === 1
            ? 'IN_PROGRESS'
            : 'PLANNED'
      tasks.push({
        id: 480001 + orderIndex * FE01_OPERATIONS.length + (operation - 1),
        orderNo,
        operationIndex: operation,
        operationName: FE01_OPERATIONS[operation - 1],
        resourceCode: resource.code,
        secondaryResourceCodes: operation < 4 ? [`RES-FIX-10${pair}`] : [],
        setupStartTime: iso(setupStart),
        setupEndTime: iso(start),
        startTime: iso(start),
        endTime: iso(end),
        qty: randomInt(random, 40, 300),
        status,
        sequenceInResource: 0,
        version: randomInt(random, 1, 3)
      })
      cursor = end + randomInt(random, 20, 50) * MINUTE
    }
  }

  // 可复现异常 1：订单 DM-SO-1005 首道工序被提前，与订单 DM-SO-1003 首道工序在同一 SMT 资源上重叠。
  const conflictTask = tasks.find(
    (task) => task.orderNo === 'DM-SO-1005' && task.operationIndex === 1
  )
  if (conflictTask) {
    const anchor = tasks.find(
      (task) => task.orderNo === 'DM-SO-1003' && task.operationIndex === 1
    )
    if (anchor) {
      const anchorStart = Date.parse(anchor.startTime ?? '')
      const ownStart = Date.parse(conflictTask.startTime ?? '')
      const shift = ownStart - (anchorStart + 30 * MINUTE)
      conflictTask.startTime = iso(ownStart - shift)
      conflictTask.endTime = iso(Date.parse(conflictTask.endTime ?? '') - shift)
      conflictTask.setupEndTime = conflictTask.startTime
      conflictTask.setupStartTime = conflictTask.startTime
    }
  }

  // 可复现异常 2：订单 DM-SO-1002 第二道工序提前 45 分钟，早于首道工序结束 → 工序依赖违例。
  const dependentTask = tasks.find(
    (task) => task.orderNo === 'DM-SO-1002' && task.operationIndex === 2
  )
  const predecessor = tasks.find(
    (task) => task.orderNo === 'DM-SO-1002' && task.operationIndex === 1
  )
  if (dependentTask && predecessor) {
    const predecessorEnd = Date.parse(predecessor.endTime ?? '')
    const start = predecessorEnd - 45 * MINUTE
    const duration =
      Date.parse(dependentTask.endTime ?? '') - Date.parse(dependentTask.startTime ?? '')
    dependentTask.startTime = iso(start)
    dependentTask.endTime = iso(start + duration)
    dependentTask.setupEndTime = dependentTask.startTime
    dependentTask.setupStartTime = dependentTask.startTime
  }

  // 按资源 + 占用起点重新生成 sequenceInResource（C4：资源内按时间顺序）。
  const byResource = new Map<string, GanttTask[]>()
  FE01_RESOURCES.forEach((resource) => byResource.set(resource.code, []))
  tasks.forEach((task) => {
    if (task.resourceCode) {
      byResource.get(task.resourceCode)?.push(task)
    }
  })
  byResource.forEach((bucket) => {
    bucket
      .sort(
        (left, right) =>
          Date.parse(left.setupStartTime ?? left.startTime ?? '') -
          Date.parse(right.setupStartTime ?? right.startTime ?? '')
      )
      .forEach((task, index) => {
        task.sequenceInResource = index + 1
      })
  })

  const rows: GanttResourceRow[] = FE01_RESOURCES.map((resource) => ({
    resourceCode: resource.code,
    resourceName: resource.name,
    resourceType: resource.type,
    tasks: (byResource.get(resource.code) ?? []).sort(
      (left, right) => (left.sequenceInResource ?? 0) - (right.sequenceInResource ?? 0)
    )
  }))

  return {
    sandboxId,
    from: iso(RANGE_START),
    to: iso(RANGE_END),
    resources: rows
  }
}
