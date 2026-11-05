<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  createGanttAdapter,
  getGanttDatasetProfile,
  GANTT_DATASET_PROFILES
} from '@/components/gantt/adapters'
import { analyzeGanttDataset } from '@/components/gantt/analysis'
import type {
  GanttAnalysis,
  GanttCalendarCheck,
  GanttConflict,
  GanttLayerFlags
} from '@/components/gantt/analysis'
import { PageSkeleton } from '@/components/common'
import { GANTT_CALENDAR_TIMEZONE_NOTE } from '@/components/gantt/calendar'
import type { GanttCalendarRowState, GanttCalendarWindowKind } from '@/components/gantt/calendar'
import GanttTimeline from '@/components/gantt/GanttTimeline.vue'
import type { GanttBarModel, GanttDataset, GanttRowModel, GanttZoom } from '@/components/gantt'
import type { GanttDatasetKey } from '@/components/gantt/adapters'
import { undoAdjustment } from '@/api/gantt'
import { ApiError, createIdempotencyKey } from '@/api/http'
import type { GanttTask, TaskAdjustRequest } from '@/types/gantt'
import { DEFAULT_SANDBOX_ID } from '@/constants/defaults'
import { getEnumLabel, getEnumOptions } from '@/constants/enums'
import type { Calendar } from '@/types/calendars'

interface RenderMetrics {
  loadMs: number
  renderMs: number
  totalMs: number
  domNodes: number
  measuredAt: string
}

/** M3-FE-04 性能自测结果：5 次加载 + 120 帧程序化滚动，口径可复现。 */
interface PerfProbe {
  dataset: string
  rounds: number
  avgMs: number
  p95Ms: number
  minMs: number
  maxMs: number
  fps: number
  avgFrameMs: number
  droppedFrames: number
  dropRate: number
  sampledFrames: number
  rowCount: number
  taskCount: number
  domNodes: number
  measuredAt: string
}

const PROBE_ROUNDS = 5
const PROBE_SCROLL_FRAMES = 120
const PROBE_DROP_THRESHOLD_MS = 25

const route = useRoute()
const router = useRouter()

const zoomOptions: Array<{ value: GanttZoom; label: string }> = [
  { value: 'hour', label: '小时' },
  { value: 'day', label: '天' },
  { value: 'week', label: '周' },
  { value: 'month', label: '月' }
]

/**
 * 联调只读窗口：后端 18085 现存数据覆盖 2026-11-01 → 11-12（与 M2-FE-01 相同的时间事实），
 * 页面只做 GET，不写入任何排程数据。
 */
const QUERY_RANGE = {
  from: new Date(Date.UTC(2026, 10, 1)).toISOString(),
  to: new Date(Date.UTC(2026, 10, 12)).toISOString()
}

/**
 * 5000 任务容量档的任务会溢出 11-05 → 11-12 的默认窗口。
 * Mock 下放宽到整档实际跨度，确保「5000 任务」档位真的是 5000 条任务参与渲染，
 * 否则按默认窗口过滤后只剩约 1/4，性能采样会失真。
 */
const CAPACITY_MOCK_RANGE = {
  from: new Date(Date.UTC(2026, 10, 1)).toISOString(),
  to: new Date(Date.UTC(2026, 11, 31)).toISOString()
}

const datasetKey = ref<GanttDatasetKey>('48x8')
const zoom = ref<GanttZoom>('day')
/** 默认真实后端；Mock 只允许显式 ?source=mock 或工具栏显式切换进入。 */
const dataSource = ref<'mock' | 'api'>('api')
const layers = reactive<GanttLayerFlags>({
  calendar: true,
  idle: false,
  dependency: true,
  heat: false,
  conflict: true,
  freeze: true
})

const dataset = ref<GanttDataset | null>(null)
const datasetIsEmpty = computed(() => dataset.value !== null && dataset.value.rows.length === 0)
const analysis = ref<GanttAnalysis | null>(null)
const metrics = ref<RenderMetrics | null>(null)
const perf = ref<PerfProbe | null>(null)
const perfRunning = ref(false)
const loading = ref(false)
const error = ref('')
const stageRef = ref<HTMLElement | null>(null)
const selectedTaskId = ref<number | null>(null)
/**
 * 同一 payload 的重试复用同一 Idempotency-Key，避免「请求已落库但响应丢失」后重复拆分/合并；
 * payload 变化（用户改参数）则换新 key。声明需早于 immediate watch，避免 TDZ。
 */
let pendingAdjustKey: { signature: string; key: string } | null = null
let loadToken = 0

const adapter = computed(() =>
  createGanttAdapter({ dataset: datasetKey.value, source: dataSource.value, mode: 'order' })
)
const profile = computed(() => getGanttDatasetProfile(datasetKey.value))
const statusLegend = computed(() => getEnumOptions('taskStatus'))
const conflictRows = computed<GanttConflict[]>(() => analysis.value?.conflicts ?? [])

const selectedBar = computed<GanttBarModel | null>(() => {
  if (selectedTaskId.value === null) {
    return null
  }
  for (const row of dataset.value?.rows ?? []) {
    const bar = row.bars.find((item) => item.taskId === selectedTaskId.value)
    if (bar) {
      return bar
    }
  }
  return null
})

const selectedRow = computed<GanttRowModel | null>(() => {
  if (!selectedBar.value) {
    return null
  }
  return (
    dataset.value?.rows.find((row) =>
      row.bars.some((bar) => bar.taskId === selectedBar.value?.taskId)
    ) ?? null
  )
})

const peakLoadText = computed(() => {
  const ratio = analysis.value?.summary.peakLoadRatio
  return ratio === null || ratio === undefined ? '—（无可比日历）' : `${Math.round(ratio * 100)}%`
})

/* ---------------------------------------------------------------------------------------------
 * M2-FE-03 人工调整（C22 POST /tasks/{id}/adjust）
 * 已冻结语义：dryRun 返回 applied=true 但不产生 adjustment 行；40901 版本过期；
 * 42201 订单级冻结/目标资源占用等不可行；40903 进行中/已完成；50100 SPLIT/MERGE 未实现；
 * 锁优先级 FROZEN > lockType > frozen:boolean；时间窗闭区间 [from,to]，零长度前端先拦（400/40000）；
 * 三层冻结（订单级 freeze_status / 任务级 FROZEN）取交集，不是互相替代。
 * ------------------------------------------------------------------------------------------- */
type AdjustSubmitOperation = 'MOVE' | 'LOCK' | 'FREEZE' | 'UNFREEZE' | 'SPLIT' | 'MERGE'

interface AdjustFeedback {
  level: 'success' | 'warning' | 'error'
  title: string
  detail: string
  code?: number
  httpStatus?: number | null
}

const adjustOperations: Array<{ value: AdjustSubmitOperation; label: string }> = [
  { value: 'MOVE', label: '移动 MOVE' },
  { value: 'LOCK', label: '锁定 LOCK' },
  { value: 'FREEZE', label: '冻结 FREEZE' },
  { value: 'UNFREEZE', label: '解冻 UNFREEZE' },
  { value: 'SPLIT', label: '拆分 SPLIT' },
  { value: 'MERGE', label: '合并 MERGE' }
]

const adjOperation = ref<AdjustSubmitOperation>('MOVE')
const adjDryRun = ref(false)
const adjResourceCode = ref('')
const adjStartTime = ref('')
const adjLockFrom = ref('')
const adjLockTo = ref('')
const adjReason = ref('')
const adjSplitQuantities = ref('')
const adjMergeTaskIds = ref<number[]>([])
const adjusting = ref(false)
const adjustFeedback = ref<AdjustFeedback | null>(null)
const lastAdjustmentId = ref<number | null>(null)

const resourceOptions = computed(() => {
  const codes = new Set<string>()
  ;(dataset.value?.rows ?? []).forEach((row) => {
    row.bars.forEach((bar) => {
      if (bar.resourceCode) {
        codes.add(bar.resourceCode)
      }
    })
  })
  return [...codes].sort().map((code) => ({ value: code, label: code }))
})

/** C36 MERGE 候选：同订单行、同工序、同资源，且未冻结/未开始/未完成的其它任务。 */
const mergeCandidates = computed(() => {
  const bar = selectedBar.value
  if (!bar) {
    return []
  }
  return (selectedRow.value?.bars ?? [])
    .filter(
      (item) =>
        item.taskId !== bar.taskId &&
        item.operationIndex === bar.operationIndex &&
        (item.resourceCode ?? null) === (bar.resourceCode ?? null) &&
        item.status !== 'FROZEN' &&
        item.status !== 'IN_PROGRESS' &&
        item.status !== 'COMPLETED'
    )
    .sort(
      (left, right) =>
        (left.sequenceInResource ?? 0) - (right.sequenceInResource ?? 0) ||
        Date.parse(left.startTime) - Date.parse(right.startTime)
    )
    .map((item) => ({
      value: item.taskId,
      label: `#${item.taskId} · qty ${item.qty ?? '—'} · ${formatMergeTime(item)}`
    }))
})

function formatMergeTime(bar: GanttBarModel): string {
  const from = localInput(bar.startTime)
  const to = localInput(bar.endTime)
  return `${from} → ${to}`
}

const selectedOrderFrozen = computed(() => selectedRow.value?.freezeStatus === 'FROZEN')
const selectedTaskFrozen = computed(() => {
  const bar = selectedBar.value
  return Boolean(bar && (bar.status === 'FROZEN' || bar.lockType === 'FROZEN' || bar.frozen))
})
/** 三层冻结取交集：订单级 FROZEN 或任务级 FROZEN 任一成立，整体即不可放宽。 */
const selectedEffectiveFrozen = computed(
  () => selectedOrderFrozen.value || selectedTaskFrozen.value
)
const selectedLockType = computed(() => selectedBar.value?.lockType ?? 'NONE')

const movePreview = computed(() => {
  const bar = selectedBar.value
  if (!bar || adjOperation.value !== 'MOVE') {
    return ''
  }
  const start = isoOfLocal(adjStartTime.value)
  if (!start || !bar.startTime || !bar.endTime) {
    return ''
  }
  const duration = Date.parse(bar.endTime) - Date.parse(bar.startTime)
  const end = new Date(Date.parse(start) + duration).toISOString()
  return `按 startTime 平移并保持原加工时长 ${Math.round(duration / 60_000)} 分钟 → 结束 ${localInput(end)}`
})

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** ISO → Element Plus datetime 输入值（本地墙钟，不含时区后缀）。 */
function localInput(value?: string | null): string {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** Element Plus datetime 输入值（本地墙钟）→ 发往后端的 UTC ISO；非法返回 null。 */
function isoOfLocal(value: string): string | null {
  if (!value) {
    return null
  }
  const time = Date.parse(value.replace(' ', 'T'))
  return Number.isFinite(time) ? new Date(time).toISOString() : null
}

function findBar(taskId: number | null): GanttBarModel | null {
  if (taskId === null) {
    return null
  }
  for (const row of dataset.value?.rows ?? []) {
    const bar = row.bars.find((item) => item.taskId === taskId)
    if (bar) {
      return bar
    }
  }
  return null
}

function applyBarDefaults(bar: GanttBarModel): void {
  adjOperation.value = 'MOVE'
  adjDryRun.value = false
  adjResourceCode.value = bar.resourceCode ?? ''
  adjStartTime.value = localInput(bar.startTime)
  adjLockFrom.value = localInput(bar.startTime)
  adjLockTo.value = localInput(bar.endTime)
  adjReason.value = ''
  adjSplitQuantities.value = ''
  adjMergeTaskIds.value = []
}

watch(
  () => selectedTaskId.value,
  (taskId) => {
    adjustFeedback.value = null
    lastAdjustmentId.value = null
    pendingAdjustKey = null
    const bar = findBar(taskId)
    if (bar) {
      applyBarDefaults(bar)
    }
  },
  { immediate: true }
)

function resetAdjustForm(): void {
  const bar = selectedBar.value
  if (!bar) {
    return
  }
  applyBarDefaults(bar)
  adjustFeedback.value = null
}

function rejectLocally(title: string, detail: string): void {
  rejectLocallyWith(40000, title, detail)
}

function rejectLocallyWith(
  code: number,
  title: string,
  detail: string,
  httpStatus = 400
): void {
  adjustFeedback.value = { level: 'warning', title, detail, code, httpStatus }
}

function parseSplitQuantities(raw: string): number[] | null {
  const tokens = raw
    .split(/[\s,，]+/)
    .map((token) => token.trim())
    .filter((token) => token.length > 0)
  if (tokens.length === 0) {
    return null
  }
  const values = tokens.map((token) => Number(token))
  return values.some((value) => !Number.isFinite(value) || value <= 0) ? null : values
}

/** MERGE 本地预校验：参与任务必须在同订单行、同工序、同资源内连续相邻。 */
function mergeAdjacencyError(bar: GanttBarModel, ids: number[]): string | null {
  const row = selectedRow.value
  if (!row) {
    return '当前选中任务不在可见行内'
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
  const positions = ids
    .map((id) => group.findIndex((item) => item.taskId === id))
    .sort((left, right) => left - right)
  if (positions.some((position) => position < 0)) {
    return '所选任务已不在当前窗口，请刷新后重选'
  }
  if (positions.some((position, index) => index > 0 && position !== positions[index - 1] + 1)) {
    return '所选任务在资源内不连续；MERGE 要求相邻任务（中间不能夹其它任务）'
  }
  return null
}

/**
 * 前端可判定的 400/40000、42200 参数问题先拦；
 * 版本、冻结、占用、相邻性等最终仍以后端（或 mock 同语义实现）为准。
 */
function buildAdjustPayload(
  operation: AdjustSubmitOperation,
  dryRun: boolean
): TaskAdjustRequest | null {
  const bar = selectedBar.value
  if (!bar) {
    return null
  }
  const payload: TaskAdjustRequest = { operation, version: bar.version, dryRun }
  if (operation === 'MOVE') {
    const start = isoOfLocal(adjStartTime.value)
    if (!start) {
      rejectLocally(
        '参数不合法（400 / 40000）：MOVE 必须填写开始时间',
        '请选择新的开始时间后再提交；MOVE 由后端按 startTime 平移并保持原加工时长。'
      )
      return null
    }
    payload.startTime = start
    if (adjResourceCode.value) {
      payload.resourceCode = adjResourceCode.value
    }
  }
  if (operation === 'LOCK') {
    const from = isoOfLocal(adjLockFrom.value)
    const to = isoOfLocal(adjLockTo.value)
    if (!from || !to) {
      rejectLocally(
        '参数不合法（400 / 40000）：锁定时间窗必须同时提供 from / to',
        '时间窗是闭区间 [from,to]，两项均必填；缺失任一项后端返回 40000。'
      )
      return null
    }
    if (Date.parse(to) <= Date.parse(from)) {
      rejectLocally(
        '参数不合法（400 / 40000）：时间窗为零长度或反向',
        '闭区间 [from,to] 必须满足 lockTo 晚于 lockFrom；零长度窗口会被数据库 CHECK 拒绝，后端返回 HTTP 400 + 40000。请至少留 1 分钟。'
      )
      return null
    }
    payload.lockFrom = from
    payload.lockTo = to
  }
  if (operation === 'FREEZE' && !adjReason.value.trim()) {
    rejectLocally(
      '参数不合法（400 / 40000）：FREEZE 必须填写原因',
      'C22 规定冻结必须带 reason，用于 task_adjustment 审计记录；未填写时后端返回 40000。'
    )
    return null
  }
  if (operation === 'SPLIT' || operation === 'MERGE') {
    if (!adjReason.value.trim()) {
      rejectLocally(
        `参数不合法（400 / 40000）：${operation} 必须填写原因`,
        'C36 规定 SPLIT/MERGE 为不可逆结构变更，必须带 reason 写入 task_adjustment 审计记录。'
      )
      return null
    }
  }
  if (operation === 'SPLIT') {
    const quantities = parseSplitQuantities(adjSplitQuantities.value)
    if (!quantities) {
      rejectLocally(
        '参数不合法（400 / 40000）：拆分数量格式错误',
        '请用逗号/空格分隔 2–10 个正数，例如「10, 20, 30」；每段必须 > 0。'
      )
      return null
    }
    if (quantities.length < 2 || quantities.length > 10) {
      rejectLocallyWith(
        42200,
        `参数不合法（42200）：拆分数量需 2–10 项，当前 ${quantities.length} 项`,
        'C36 只接受 2–10 段 SPLIT；请调整分段数。'
      )
      return null
    }
    const qty = bar.qty
    if (qty === null || qty === undefined || qty <= 0) {
      rejectLocallyWith(
        42200,
        '参数不合法（42200）：源任务 qty 为空，无法拆分',
        'C36 要求源任务 qty 非空且 > 0；请在排程结果中确认该任务数量。'
      )
      return null
    }
    const total = quantities.reduce((sum, value) => sum + value, 0)
    if (Math.abs(total - qty) > 1e-4) {
      rejectLocallyWith(
        42200,
        `参数不合法（42200）：拆分数量之和必须等于 qty`,
        `当前拆分合计 ${total}，源任务 qty ${qty}，差 ${Math.abs(total - qty)}；后端容差 1e-4。`
      )
      return null
    }
    payload.splitQuantities = quantities
  }
  if (operation === 'MERGE') {
    const ids = [...new Set([...adjMergeTaskIds.value, bar.taskId])]
    if (ids.length < 2 || ids.length > 10) {
      rejectLocallyWith(
        42201,
        '合并不可行（42201）：需选择 2–10 个任务',
        'mergeTaskIds 必须包含当前任务自身，合计 2–10 项。'
      )
      return null
    }
    const adjacency = mergeAdjacencyError(bar, ids)
    if (adjacency) {
      rejectLocallyWith(42201, '合并不可行（42201）：参与任务不相邻', adjacency)
      return null
    }
    payload.mergeTaskIds = ids
  }
  if (adjReason.value.trim()) {
    payload.reason = adjReason.value.trim()
  }
  return payload
}

type BarSnapshot = Pick<
  GanttBarModel,
  | 'startTime'
  | 'endTime'
  | 'status'
  | 'frozen'
  | 'version'
  | 'lockType'
  | 'resourceCode'
  | 'qty'
  | 'late'
>

function captureBar(bar: GanttBarModel): BarSnapshot {
  return {
    startTime: bar.startTime,
    endTime: bar.endTime,
    status: bar.status,
    frozen: bar.frozen,
    version: bar.version,
    lockType: bar.lockType,
    resourceCode: bar.resourceCode,
    qty: bar.qty,
    late: bar.late
  }
}

/** 乐观应用：dryRun 返回的「将要写入」视图先行落到本地，真实请求失败时用快照回滚。 */
function applyLocalTask(bar: GanttBarModel, task: GanttTask): void {
  if (task.startTime) {
    bar.startTime = task.startTime
  }
  if (task.endTime) {
    bar.endTime = task.endTime
  }
  bar.status = task.status
  bar.frozen = task.status === 'FROZEN'
  bar.version = task.version
  bar.lockType = task.lockType ?? 'NONE'
  bar.resourceCode = task.resourceCode
  bar.qty = task.qty
  bar.late = task.late
}

function restoreBar(bar: GanttBarModel, snapshot: BarSnapshot): void {
  bar.startTime = snapshot.startTime
  bar.endTime = snapshot.endTime
  bar.status = snapshot.status
  bar.frozen = snapshot.frozen
  bar.version = snapshot.version
  bar.lockType = snapshot.lockType
  bar.resourceCode = snapshot.resourceCode
  bar.qty = snapshot.qty
  bar.late = snapshot.late
}

async function submitAdjust(operation: AdjustSubmitOperation): Promise<void> {
  const bar = selectedBar.value
  if (!bar || adjusting.value) {
    return
  }
  adjustFeedback.value = null
  const payload = buildAdjustPayload(operation, adjDryRun.value)
  if (!payload) {
    return
  }
  const adjust = adapter.value.adjust
  if (!adjust) {
    adjustFeedback.value = {
      level: 'error',
      title: '当前数据源不支持人工调整',
      detail: '请切换到 mock 数据源或连接 18085 后端后重试。',
      code: 50100,
      httpStatus: null
    }
    return
  }

  adjusting.value = true
  const snapshot = captureBar(bar)
  try {
    if (!payload.dryRun) {
      // 写前预校验：dryRun=true 不落库，只返回完整校验与目标视图。
      const precheck = await adjust(
        bar.taskId,
        { ...payload, dryRun: true },
        createIdempotencyKey('gantt-pre')
      )
      if (!precheck.applied || precheck.conflicts.length > 0) {
        adjustFeedback.value = {
          level: 'warning',
          title: 'dryRun 预校验未通过，未提交真实调整',
          detail:
            precheck.conflicts.map((item) => item.message).join('；') ||
            'applied=false 且无冲突明细，后端未给出可执行视图。',
          code: 42201,
          httpStatus: 200
        }
        return
      }
      if (precheck.task) {
        applyLocalTask(bar, precheck.task)
      }
    }

    const signature = JSON.stringify(payload)
    if (!pendingAdjustKey || pendingAdjustKey.signature !== signature) {
      pendingAdjustKey = { signature, key: createIdempotencyKey('gantt-adj') }
    }
    const result = await adjust(bar.taskId, payload, pendingAdjustKey.key)
    if (!result.dryRun) {
      pendingAdjustKey = null
    }
    lastAdjustmentId.value = result.adjustmentId
    const created = result.createdTaskIds?.length
      ? `；新建任务 ${result.createdTaskIds.join(', ')}`
      : ''
    const removed = result.removedTaskIds?.length
      ? `；软删任务 ${result.removedTaskIds.join(', ')}`
      : ''
    adjustFeedback.value = {
      level: 'success',
      title: result.dryRun
        ? 'dryRun 校验通过：applied=true，未产生 adjustment 行'
        : `调整成功（adjustmentId=${result.adjustmentId}）`,
      detail: result.dryRun
        ? `dryRun=true：后端只做完整校验与「将要写入」视图计算，未改 task、未写 task_adjustment（adjustmentId=null）。目标资源 ${result.task?.resourceCode ?? '—'}，目标时间 ${localInput(result.task?.startTime)} → ${localInput(result.task?.endTime)}，目标版本 ${result.task?.version ?? bar.version}，qty ${result.task?.qty ?? '—'}。`
        : `applied=${result.applied}，任务版本 ${bar.version} → ${result.task?.version ?? '—'}；资源 ${result.task?.resourceCode ?? '—'}；时间 ${localInput(result.task?.startTime)} → ${localInput(result.task?.endTime)}；qty ${result.task?.qty ?? '—'}${created}${removed}。已写入 task_adjustment，可撤销本次。`,
      code: 0,
      httpStatus: 200
    }
    if (!result.dryRun) {
      await loadDataset(true)
    }
  } catch (exception) {
    restoreBar(bar, snapshot)
    adjustFeedback.value = describeAdjustError(exception, {
      orderFrozen: selectedOrderFrozen.value,
      version: bar.version,
      operation
    })
  } finally {
    adjusting.value = false
  }
}

async function undoLastAdjustment(): Promise<void> {
  const adjustmentId = lastAdjustmentId.value
  const bar = selectedBar.value
  if (!adjustmentId || !bar || adjusting.value) {
    return
  }
  adjusting.value = true
  try {
    const result = await undoAdjustment(adjustmentId, createIdempotencyKey('gantt-undo'))
    adjustFeedback.value = {
      level: 'success',
      title: `已撤销最近一次调整（adjustmentId=${adjustmentId}）`,
      detail: `任务版本 → ${result.task?.version ?? '—'}；时间 ${localInput(result.task?.startTime)} → ${localInput(result.task?.endTime)}；资源 ${result.task?.resourceCode ?? '—'}。只允许撤销该任务栈最近一次调整。`,
      code: 0,
      httpStatus: 200
    }
    lastAdjustmentId.value = null
    await loadDataset(true)
  } catch (exception) {
    adjustFeedback.value = describeAdjustError(exception, {
      orderFrozen: selectedOrderFrozen.value,
      version: bar.version,
      operation: 'UNDO'
    })
  } finally {
    adjusting.value = false
  }
}

/**
 * 错误码 → 用户可读提示。保留后端原文作为「技术详情」，不把原文当唯一提示，也不改写语义。
 * 42201 有两种冻结/占用原因：按订单级冻结上下文与后端 message 判定，文案相互区分。
 */
function describeAdjustError(
  exception: unknown,
  context: { orderFrozen: boolean; version: number; operation: string }
): AdjustFeedback {
  if (!(exception instanceof ApiError)) {
    return {
      level: 'error',
      title: '调整失败：网络或前端异常',
      detail: exception instanceof Error ? exception.message : String(exception)
    }
  }
  const detail = `HTTP ${exception.httpStatus ?? '—'} · code ${exception.code}${
    exception.traceId ? ` · traceId ${exception.traceId.slice(0, 8)}` : ''
  } · 后端原文：${exception.message}`
  const base = { code: exception.code, httpStatus: exception.httpStatus }
  if (exception.code === 40901) {
    return {
      ...base,
      level: 'warning',
      title: '版本已过期（40901）：任务已被重新排程或他人调整',
      detail: `前端提交 version=${context.version}，与后端当前版本不一致，后端已拒绝且未做任何改动。请重新加载数据、确认最新时段后再提交。\n${detail}`
    }
  }
  if (exception.code === 40903) {
    return {
      ...base,
      level: 'warning',
      title: '任务已执行（40903）：进行中 / 已完成任务禁止人工调整',
      detail: `IN_PROGRESS / COMPLETED 任务不允许 MOVE/LOCK/FREEZE/UNFREEZE/SPLIT/MERGE（执行事实优先于人工调整）。\n${detail}`
    }
  }
  if (exception.code === 42201) {
    if (context.orderFrozen) {
      return {
        ...base,
        level: 'error',
        title: '订单级已冻结（42201）：任务级不可放宽',
        detail: `订单 freeze_status=FROZEN 与任务级冻结取交集，MOVE/LOCK/UNFREEZE 一律被拒（FREEZE 只会更收紧）；需先由计划员解冻订单，任务级不能单方面放宽。\n${detail}`
      }
    }
    if (/已有任务占用/.test(exception.message)) {
      return {
        ...base,
        level: 'error',
        title: '目标资源该时段已被占用（42201）',
        detail: `目标资源在目标时间窗内已有其他任务占用，后端在请求阶段 fail fast；请换时段或换资源后重试（未做任何改动）。\n${detail}`
      }
    }
    if (/非可用时段/.test(exception.message)) {
      return {
        ...base,
        level: 'error',
        title: '目标时段落在资源日历非可用时段（42201）',
        detail: `目标时间窗与 C14 资源日历时段的可用窗口不相交（班次休息 / 非排班日 / 非工作例外日）；请调整到工作日历内。\n${detail}`
      }
    }
    if (/无交集|更宽/.test(exception.message)) {
      return {
        ...base,
        level: 'error',
        title: '超出订单锁窗 / 任务锁窗（42201）',
        detail: `任务级窗口不得比订单级锁窗更宽（三层冻结取交集）；请把时间或资源收敛到锁窗内。\n${detail}`
      }
    }
    return {
      ...base,
      level: 'error',
      title: '调整不可行（42201）：日历 / 依赖 / 资源 / 锁窗校验未通过',
      detail: `常见原因：目标资源占用、目标时段非可用、工序依赖顺序违例、或超出订单/任务锁窗。后端原文见下方。\n${detail}`
    }
  }
  if (exception.code === 40900) {
    return {
      ...base,
      level: 'warning',
      title: '幂等冲突（40900）：同一 Idempotency-Key 已被使用',
      detail: `该 Idempotency-Key 对应的调整已执行或正在执行；本页对同一 payload 的失败重试会复用原 key，改参数后会生成新 key。请刷新后确认任务版本再决定是否重试。\n${detail}`
    }
  }
  if (exception.code === 50100) {
    return {
      ...base,
      level: 'warning',
      title: '未实现（50100）：当前后端版本不支持该操作',
      detail: `C36 SPLIT/MERGE 已上线；出现 50100 说明连接的是旧后端或该操作未在该环境启用。本次请求未产生任何调整记录。\n${detail}`
    }
  }
  if (exception.code === 42200) {
    return {
      ...base,
      level: 'warning',
      title: '结构变更不合法（42200）：SPLIT / MERGE 数量校验失败',
      detail: `SPLIT 要求 splitQuantities 2–10 项、每项 > 0、和等于源任务 qty（容差 1e-4）；MERGE 要求参与任务 qty 非空且 > 0。\n${detail}`
    }
  }
  if (exception.code === 40000) {
    return {
      ...base,
      level: 'warning',
      title: '参数不合法（400 / 40000）：闭区间或必填项校验失败',
      detail: `时间窗是闭区间 [from,to]，from==to 的零长度窗会被数据库 CHECK 拒绝；MOVE 需 startTime，LOCK 需 lockFrom/lockTo 且 to>from，FREEZE/SPLIT/MERGE 需 reason。\n${detail}`
    }
  }
  if (exception.code === 40400) {
    return {
      ...base,
      level: 'error',
      title: '任务不存在（40400）',
      detail: `该任务已被删除或不在当前场景 / 窗口，请刷新后重试。\n${detail}`
    }
  }
  return {
    ...base,
    level: 'error',
    title: `调整失败（code ${exception.code}）`,
    detail
  }
}

const lateOrders = computed(() => (dataset.value?.rows ?? []).filter((row) => row.late))
const lateMinutesTotal = computed(() =>
  lateOrders.value.reduce((total, row) => total + (row.lateMinutes ?? 0), 0)
)
/** 延期任务按任务条 late 标记计数（C29 同字段），不用订单行 lateTaskCount 二次累加。 */
const lateTaskCount = computed(() =>
  (dataset.value?.rows ?? []).reduce(
    (total, row) => total + row.bars.filter((bar) => bar.late === true).length,
    0
  )
)
const frozenOrders = computed(() =>
  (dataset.value?.rows ?? []).filter((row) => row.freezeStatus === 'FROZEN')
)
const lockedTaskCount = computed(() => {
  const ids = new Set<number>()
  ;(dataset.value?.rows ?? []).forEach((row) => {
    row.bars.forEach((bar) => {
      if (
        bar.frozen ||
        bar.lockType === 'FROZEN' ||
        bar.lockType === 'TIME_WINDOW' ||
        bar.lockType === 'RESOURCE'
      ) {
        ids.add(bar.taskId)
      }
    })
  })
  return ids.size
})
const calendarChecks = computed<GanttCalendarCheck[]>(() => analysis.value?.calendarChecks ?? [])

const hasCalendarGap = computed(() => {
  if (!analysis.value) {
    return false
  }
  const summary = analysis.value.calendarSummary
  return (
    !analysis.value.calendarResourceListLoaded ||
    summary.unboundCount + summary.missingCount + summary.errorCount > 0
  )
})

/** 订单行冲突数：按任务归属订单去重，跨行冲突会在两行各计一次。 */
const conflictCountByOrder = computed(() => {
  const map = new Map<string, number>()
  const rowKeyByTask = new Map<number, string>()
  ;(dataset.value?.rows ?? []).forEach((row) => {
    row.bars.forEach((bar) => rowKeyByTask.set(bar.taskId, row.key))
  })
  const seen = new Set<string>()
  conflictRows.value.forEach((conflict, index) => {
    conflict.taskIds.forEach((taskId) => {
      const rowKey = rowKeyByTask.get(taskId)
      if (!rowKey) {
        return
      }
      const key = `${rowKey}:${index}`
      if (seen.has(key)) {
        return
      }
      seen.add(key)
      map.set(rowKey, (map.get(rowKey) ?? 0) + 1)
    })
  })
  return map
})

interface OrderTableView {
  orderNo: string
  materialCode: string
  dueDate: string
  priorityLabel: string
  freezeStatus: string
  taskCount: number
  lateText: string
  conflictCount: number
  resourceCodes: string
}

const orderRowsView = computed<OrderTableView[]>(() =>
  (dataset.value?.rows ?? []).map((row) => ({
    orderNo: row.key,
    materialCode: row.subLabel?.split(' · ')[0] ?? '—',
    dueDate: row.dueDate ?? '—',
    priorityLabel: priorityText(row.priority),
    freezeStatus: row.freezeStatus === 'FROZEN' ? '订单冻结' : '未冻结',
    taskCount: row.bars.length,
    lateText: row.late ? (row.lateMinutes == null ? '延期' : `延期 ${row.lateMinutes} 分`) : '—',
    conflictCount: conflictCountByOrder.value.get(row.key) ?? 0,
    resourceCodes: (row.resourceCodes ?? []).join('、')
  }))
)

function priorityText(priority?: string): string {
  if (priority === 'URGENT') {
    return '紧急'
  }
  if (priority === 'HIGH') {
    return '高'
  }
  if (priority === 'LOW') {
    return '低'
  }
  return '普通'
}

function daysText(days: number[]): string {
  const sorted = [...days].sort((left, right) => left - right)
  if (sorted.length === 7) {
    return '每天'
  }
  if (sorted.join(',') === '1,2,3,4,5,6') {
    return '周一至周六'
  }
  if (sorted.join(',') === '1,2,3,4,5') {
    return '周一至周五'
  }
  return sorted.map((day) => `周${'一二三四五六日'[day - 1] ?? day}`).join('、')
}

function shiftTextOf(calendar: Calendar | null): string {
  if (!calendar) {
    return '—'
  }
  return calendar.shifts
    .map((shift) => `${shift.startTime}-${shift.endTime}（${daysText(shift.daysOfWeek)}）`)
    .join('；')
}

function holidayTextOf(calendar: Calendar | null): string {
  const holidays = calendar?.holidays ?? []
  if (!holidays.length) {
    return '无例外日'
  }
  return holidays.map((holiday) => `${holiday.date} ${holiday.name}（${holiday.kind}）`).join('；')
}

interface CalendarRowView extends GanttCalendarRowState {
  shifts: string
  holidays: string
}

const calendarRowsView = computed<CalendarRowView[]>(() => {
  const bindingMap = new Map(
    (dataset.value?.calendarBindings ?? []).map((binding) => [binding.resourceCode, binding])
  )
  return (analysis.value?.calendarRows ?? []).map((state) => {
    const calendar = bindingMap.get(state.rowKey)?.calendar ?? null
    return {
      ...state,
      shifts: shiftTextOf(calendar),
      holidays: holidayTextOf(calendar)
    }
  })
})

const calendarWindowRows = computed(() => (analysis.value?.calendarWindows ?? []).slice(0, 20))

function calendarStateTag(
  state: GanttCalendarRowState['state']
): 'success' | 'warning' | 'danger' | 'info' {
  if (state === 'BOUND') {
    return 'success'
  }
  if (state === 'UNBOUND') {
    return 'warning'
  }
  return 'danger'
}

function calendarWindowKindLabel(kind: GanttCalendarWindowKind): string {
  if (kind === 'HOLIDAY') {
    return '非工作例外日'
  }
  if (kind === 'NON_WORKDAY') {
    return '非排班日'
  }
  return '班次休息'
}

function formatWindowTime(value: string): string {
  const date = new Date(value)
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(
    2,
    '0'
  )} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function conflictTypeLabel(type: GanttConflict['type']): string {
  if (type === 'DEPENDENCY_VIOLATION') {
    return '依赖违例'
  }
  if (type === 'SECONDARY_RESOURCE_OVERLAP') {
    return '辅资源重叠'
  }
  return '资源重叠'
}

function conflictTagType(type: GanttConflict['type']): 'danger' | 'warning' {
  return type === 'DEPENDENCY_VIOLATION' ? 'danger' : 'warning'
}

function nextFrame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()))
}

async function loadDataset(preserveSelection = false): Promise<void> {
  const token = (loadToken += 1)
  const keepTaskId = preserveSelection ? selectedTaskId.value : null
  loading.value = true
  error.value = ''
  dataset.value = null
  analysis.value = null
  metrics.value = null
  if (!preserveSelection) {
    selectedTaskId.value = null
  }
  try {
    const startedAt = performance.now()
    const range =
      dataSource.value === 'mock' && datasetKey.value === '5000x50'
        ? CAPACITY_MOCK_RANGE
        : QUERY_RANGE
    const data = await adapter.value.load({
      sandboxId: DEFAULT_SANDBOX_ID,
      from: range.from,
      to: range.to
    })
    const afterLoad = performance.now()
    dataset.value = data
    analysis.value = analyzeGanttDataset(data)
    if (
      keepTaskId !== null &&
      data.rows.some((row) => row.bars.some((bar) => bar.taskId === keepTaskId))
    ) {
      selectedTaskId.value = keepTaskId
      const bar = findBar(keepTaskId)
      if (bar) {
        applyBarDefaults(bar)
      }
    } else {
      selectedTaskId.value = null
    }
    await nextTick()
    await nextFrame()
    await nextFrame()
    if (token !== loadToken) {
      return
    }
    const afterRender = performance.now()
    metrics.value = {
      loadMs: Math.round((afterLoad - startedAt) * 100) / 100,
      renderMs: Math.round((afterRender - afterLoad) * 100) / 100,
      totalMs: Math.round((afterRender - startedAt) * 100) / 100,
      domNodes: stageRef.value?.querySelectorAll('*').length ?? 0,
      measuredAt: new Date().toLocaleString('zh-CN', { hour12: false })
    }
  } catch (exception) {
    if (token === loadToken) {
      error.value = exception instanceof Error ? exception.message : '订单甘特数据加载失败'
      dataset.value = null
      analysis.value = null
    }
  } finally {
    if (token === loadToken) {
      loading.value = false
    }
  }
}

function percentileOf(values: number[], ratio: number): number {
  const sorted = [...values].sort((left, right) => left - right)
  const index = Math.min(
    sorted.length - 1,
    Math.max(0, Math.ceil((ratio / 100) * sorted.length) - 1)
  )
  return Math.round(sorted[index] * 100) / 100
}

/**
 * 可复现测量：把 .gantt-scroll 在 120 帧内从顶滚到底，记录相邻帧间隔；
 * FPS = 采样帧数 / 累计帧间隔，掉帧 = 帧间隔 > 25ms 的帧数（约 40fps 以下）。
 */
function measureScrollFps(
  element: HTMLElement,
  frames: number
): Promise<{
  fps: number
  avgFrameMs: number
  droppedFrames: number
  dropRate: number
  sampledFrames: number
}> {
  return new Promise((resolve) => {
    const max = Math.max(element.scrollHeight - element.clientHeight, 0)
    const step = max > 0 ? Math.max(Math.floor(max / frames), 1) : 0
    element.scrollTop = 0
    let last = 0
    let sampled = 0
    let elapsed = 0
    let dropped = 0
    const tick = (now: number): void => {
      if (last !== 0) {
        const delta = now - last
        elapsed += delta
        sampled += 1
        if (delta > PROBE_DROP_THRESHOLD_MS) {
          dropped += 1
        }
      }
      last = now
      if (step > 0) {
        element.scrollTop = Math.min(max, element.scrollTop + step)
      }
      if (sampled < frames) {
        requestAnimationFrame(tick)
        return
      }
      resolve({
        fps: elapsed > 0 ? Math.round((sampled * 1000 * 10) / elapsed) / 10 : 0,
        avgFrameMs: sampled > 0 ? Math.round((elapsed / sampled) * 100) / 100 : 0,
        droppedFrames: dropped,
        dropRate: sampled > 0 ? Math.round((dropped / sampled) * 1000) / 10 : 0,
        sampledFrames: sampled
      })
    }
    requestAnimationFrame(tick)
  })
}

async function runPerfProbe(): Promise<void> {
  if (perfRunning.value) {
    return
  }
  perfRunning.value = true
  perf.value = null
  try {
    const totals: number[] = []
    for (let round = 0; round < PROBE_ROUNDS; round += 1) {
      await loadDataset(true)
      if (metrics.value) {
        totals.push(metrics.value.totalMs)
      }
    }
    if (totals.length === 0) {
      return
    }
    const scrollElement =
      stageRef.value?.querySelector<HTMLElement>('.gantt-scroll') ?? null
    const scroll = scrollElement
      ? await measureScrollFps(scrollElement, PROBE_SCROLL_FRAMES)
      : { fps: 0, avgFrameMs: 0, droppedFrames: 0, dropRate: 0, sampledFrames: 0 }
    if (scrollElement) {
      scrollElement.scrollTop = 0
    }
    perf.value = {
      dataset: datasetKey.value,
      rounds: totals.length,
      avgMs: Math.round((totals.reduce((sum, value) => sum + value, 0) / totals.length) * 100) / 100,
      p95Ms: percentileOf(totals, 95),
      minMs: Math.round(Math.min(...totals) * 100) / 100,
      maxMs: Math.round(Math.max(...totals) * 100) / 100,
      fps: scroll.fps,
      avgFrameMs: scroll.avgFrameMs,
      droppedFrames: scroll.droppedFrames,
      dropRate: scroll.dropRate,
      sampledFrames: scroll.sampledFrames,
      rowCount: dataset.value?.rows.length ?? 0,
      taskCount: analysis.value?.summary.taskCount ?? 0,
      domNodes: stageRef.value?.querySelectorAll('*').length ?? 0,
      measuredAt: new Date().toLocaleString('zh-CN', { hour12: false })
    }
  } finally {
    perfRunning.value = false
  }
}

function readQuery(): void {
  const query = route.query
  const key = String(query.dataset ?? '')
  datasetKey.value =
    key === '100x10' || key === '500x50' || key === '5000x50' || key === '48x8' ? key : '48x8'
  const nextZoom = String(query.zoom ?? '')
  zoom.value = nextZoom === 'hour' || nextZoom === 'week' || nextZoom === 'month' ? nextZoom : 'day'
  dataSource.value = String(query.source ?? '') === 'mock' ? 'mock' : 'api'
  if (String(query.heat ?? '') === '1') {
    layers.heat = true
  }
}

readQuery()

watch([datasetKey, dataSource], () => {
  perf.value = null
  void loadDataset()
})

function onSelect(taskId: number): void {
  selectedTaskId.value = selectedTaskId.value === taskId ? null : taskId
}

function goResourceView(): void {
  void router.push({ path: '/gantt', query: { source: dataSource.value, zoom: zoom.value } })
}

onMounted(() => {
  void loadDataset()
})
</script>

<template>
  <div class="gantt-page">
    <h2 class="page-title">订单甘特图（M3 W7 · 虚拟滚动 + C36 调整）</h2>

    <el-card class="page-card gantt-toolbar-card" shadow="never">
      <div class="toolbar">
        <el-form inline size="small">
          <el-form-item label="数据档">
            <el-select v-model="datasetKey" style="width: 260px">
              <el-option
                v-for="item in GANTT_DATASET_PROFILES"
                :key="item.key"
                :label="item.label"
                :value="item.key"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="时间轴">
            <el-select v-model="zoom" style="width: 110px">
              <el-option
                v-for="option in zoomOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="数据源">
            <el-select v-model="dataSource" style="width: 176px">
              <el-option label="API（后端真实数据）" value="api" />
              <el-option label="Mock（合成数据）" value="mock" />
            </el-select>
          </el-form-item>
          <el-form-item label="图层">
            <el-checkbox v-model="layers.calendar">C14 非工时校验</el-checkbox>
            <el-checkbox v-model="layers.idle" disabled>可插空档（资源口径）</el-checkbox>
            <el-checkbox v-model="layers.dependency">依赖</el-checkbox>
            <el-checkbox v-model="layers.heat">负载热力</el-checkbox>
            <el-checkbox v-model="layers.conflict">冲突高亮</el-checkbox>
            <el-checkbox v-model="layers.freeze">冻结/锁标识</el-checkbox>
          </el-form-item>
        </el-form>
        <div class="toolbar__actions">
          <el-tag
            class="source-badge"
            :type="dataSource === 'api' ? 'success' : 'warning'"
            effect="dark"
            size="large"
          >
            {{ dataSource === 'api' ? '数据源：API（后端真实数据）' : '数据源：Mock（合成数据）' }}
          </el-tag>
          <el-button size="small" @click="goResourceView">资源甘特视图</el-button>
          <el-button size="small" :loading="loading" @click="loadDataset()">重新加载</el-button>
        </div>
      </div>

    </el-card>

    <el-alert v-if="error" class="page-card" type="error" :closable="false" :title="error" />

    <el-card class="page-card" shadow="never">
      <template #header>
        订单甘特 · {{ profile.label }} · 缩放
        {{ zoomOptions.find((item) => item.value === zoom)?.label ?? zoom }}
      </template>
      <div ref="stageRef" v-loading="loading" class="stage">
        <PageSkeleton v-if="!dataset && loading" :rows="10" :show-title="false" />
        <el-empty
          v-else-if="datasetIsEmpty"
          description="该数据档在所选时间窗内没有订单任务（可能尚未排产）"
        >
          <template #default>
            <p class="empty-hint">
              先确认该方案已排产；仍为空时切换数据档或重新加载，Mock 数据档请确认已切换数据源。
            </p>
            <el-button size="small" type="primary" @click="loadDataset()">重新加载</el-button>
          </template>
        </el-empty>
        <GanttTimeline
          v-else-if="dataset"
          :dataset="dataset"
          :zoom="zoom"
          :analysis="analysis"
          :layers="layers"
          :selected-task-id="selectedTaskId"
          @select="onSelect"
        />
        <el-empty v-else description="暂无订单甘特数据：请先加载排产结果，或检查筛选条件" />
      </div>
      <p class="perf-note">
        行头展示订单号、物料/交期、优先级、延期分钟、订单冻结与资源日历绑定比例；行内橙色虚线为 C29
        交期线，超出交期的任务条带红色左边。点击任务条选中（M2-FE-03 调整目标），再次点击取消。
      </p>
      <p class="perf-note">
        当前选中：<strong>{{
          selectedBar ? `#${selectedBar.taskId} ${selectedBar.label}` : '无'
        }}</strong>
        <span v-if="selectedRow">（订单 {{ selectedRow.key }}）</span>
      </p>
    </el-card>

    <div class="legend legend--below">
      <span class="legend__title">状态配色：</span>
      <span v-for="item in statusLegend" :key="item.value" class="legend__item">
        <i class="legend__dot" :class="`tag-${item.tagType}`" />{{ item.label }}
      </span>
      <span class="legend__item"><i class="legend__setup" />换型窗口</span>
      <span class="legend__item"><i class="legend__frozen">冻</i>冻结（订单级/任务级取交集）</span>
      <span class="legend__item"><i class="legend__lock">窗</i>工时窗锁 / 资锁</span>
      <span class="legend__item"><i class="legend__conflict" />冲突（红色描边）</span>
      <span class="legend__item"><i class="legend__calendar" />C14 非工时校验（任务级）</span>
      <span class="legend__item"><i class="legend__due" />订单交期线（C29 dueDateLine）</span>
    </div>

    <el-alert v-if="analysis && hasCalendarGap" class="page-card" type="warning" :closable="false">
      <template #title
        >日历数据未接入 / 存在缺口（相关资源不计算停机与热力，不使用推定值兜底）</template
      >
      <ul class="issue-list">
        <li v-if="!analysis.calendarResourceListLoaded">
          GET /resources 整体不可用，所有资源日历均无法解析。
        </li>
        <li v-for="issue in analysis.calendarIssues" :key="issue">{{ issue }}</li>
      </ul>
    </el-alert>

    <p class="scope-note">
      一行一订单，行内为作业任务；任务时间与资源甘特同源（同一份任务集合），日历停机、冲突、热力、
      lateMinutes、lockType 均由同一套 analysis 口径计算，不存在「订单甘特另算一遍」。 C14 资源日历经
      GET /resources + GET /calendars/{id} 补齐。停机窗口与资源日历按资源编码展示（下方表格），
      不按订单行叠加：订单跨多资源，叠加会把「资源停机」误读为「订单停机」；订单图为任务级非工时校验。
      M2-FE-03 的移动/锁定/冻结在本页选中任务后操作。
    </p>

    <el-card class="page-card" shadow="never">
      <template #header>只读指标（{{ profile.label }}）</template>
      <el-descriptions v-if="dataset && analysis" :column="4" border size="small">
        <el-descriptions-item label="订单 / 任务">
          {{ analysis.summary.rowCount }} / {{ analysis.summary.taskCount }}
        </el-descriptions-item>
        <el-descriptions-item label="涉及资源（去重）">
          {{ analysis.summary.resourceCount }}
        </el-descriptions-item>
        <el-descriptions-item label="冲突（任务数）">
          <strong :class="{ 'is-danger': analysis.summary.conflictCount > 0 }">
            {{ analysis.summary.conflictCount }}（{{ analysis.summary.conflictTaskCount }}）
          </strong>
        </el-descriptions-item>
        <el-descriptions-item label="依赖违例 / 依赖边">
          {{ analysis.summary.dependencyViolationCount }} /
          {{ analysis.summary.dependencyLinkCount }}
        </el-descriptions-item>
        <el-descriptions-item label="延期订单 / 延期任务">
          {{ lateOrders.length }} / {{ lateTaskCount }}
          <span class="muted">（延期合计 {{ lateMinutesTotal }} 分钟）</span>
        </el-descriptions-item>
        <el-descriptions-item label="冻结订单 / 锁定任务">
          {{ frozenOrders.length }} / {{ lockedTaskCount }}
        </el-descriptions-item>
        <el-descriptions-item label="日历校验（任务 / 非工时分钟）">
          {{ calendarChecks.length }} /
          {{ calendarChecks.reduce((total, item) => total + item.offCalendarMinutes, 0) }}
        </el-descriptions-item>
        <el-descriptions-item label="峰值负载（订单行日历工作分钟）">
          {{ peakLoadText }}
        </el-descriptions-item>
        <el-descriptions-item label="资源日历绑定">
          {{ analysis.calendarSummary.boundCount }} / {{ analysis.summary.resourceCount }}
          <span class="muted"
            >（未绑 {{ analysis.calendarSummary.unboundCount }} · 缺失
            {{ analysis.calendarSummary.missingCount }} · 失败
            {{ analysis.calendarSummary.errorCount }}）</span
          >
        </el-descriptions-item>
        <el-descriptions-item label="停机窗口 / 覆盖天数">
          {{ analysis.calendarSummary.windowCount }} / {{ analysis.calendarSummary.coveredDays }} 天
          <span class="muted">（未覆盖 {{ analysis.calendarSummary.uncoveredDays }} 天）</span>
        </el-descriptions-item>
        <el-descriptions-item label="热力可比资源">
          {{ analysis.calendarSummary.heatComparableCount }} / {{ analysis.summary.resourceCount }}
        </el-descriptions-item>
        <el-descriptions-item label="未排程任务">
          {{ dataset.unscheduledTaskCount ?? 0 }}
        </el-descriptions-item>
        <el-descriptions-item label="加载 / 绘制">
          <span v-if="metrics">
            {{ metrics.loadMs }} ms / {{ metrics.renderMs }} ms（合计 {{ metrics.totalMs }} ms）
          </span>
          <span v-else>测量中…</span>
        </el-descriptions-item>
        <el-descriptions-item label="DOM 节点数">
          {{ metrics?.domNodes ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="测量时间" :span="2">
          {{ metrics?.measuredAt ?? '—' }}
        </el-descriptions-item>
      </el-descriptions>
      <el-empty v-else description="暂无订单甘特数据" />
      <p class="perf-note">
        口径：订单视图复用与资源视图同一份任务时间与 C14 日历解析；热力按「订单行 × 日」聚合，
        分母为当日该订单涉及资源的日历工作分钟合计（同一资源同日只计一次），未绑 / 缺失资源的任务
        不计入分母并明确提示不可比。
      </p>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header>
        性能自测（M3-FE-04 · 5000 任务虚拟滚动）
        <span class="muted">（结果只在当前页面内，不落库）</span>
      </template>
      <div class="perf-probe">
        <el-button type="primary" :loading="perfRunning" @click="runPerfProbe">
          运行（{{ PROBE_ROUNDS }} 次加载 + {{ PROBE_SCROLL_FRAMES }} 帧滚动）
        </el-button>
        <span class="muted">
          虚拟窗口参数：行高 34px · 视口 460px（≈13 行） · 纵向缓冲 6 行 · 横向缓冲 240px
        </span>
      </div>
      <el-descriptions v-if="perf" class="perf-probe-result" :column="4" border size="small">
        <el-descriptions-item label="数据档 / 采样轮次">
          {{ perf.dataset }} / {{ perf.rounds }}
        </el-descriptions-item>
        <el-descriptions-item label="首屏合计 avg / P95">
          {{ perf.avgMs }} ms / {{ perf.p95Ms }} ms
        </el-descriptions-item>
        <el-descriptions-item label="首屏 min / max">
          {{ perf.minMs }} ms / {{ perf.maxMs }} ms
        </el-descriptions-item>
        <el-descriptions-item label="滚动 FPS / 平均帧间隔">
          {{ perf.fps }} / {{ perf.avgFrameMs }} ms
        </el-descriptions-item>
        <el-descriptions-item label="掉帧 / 掉帧率">
          {{ perf.droppedFrames }} / {{ perf.dropRate }}%（{{ perf.sampledFrames }} 帧，>25ms 记为掉帧）
        </el-descriptions-item>
        <el-descriptions-item label="行 / 任务">
          {{ perf.rowCount }} / {{ perf.taskCount }}
        </el-descriptions-item>
        <el-descriptions-item label="DOM 节点数">
          {{ perf.domNodes }}
        </el-descriptions-item>
        <el-descriptions-item label="测量时间" :span="2">{{ perf.measuredAt }}</el-descriptions-item>
      </el-descriptions>
      <p class="perf-note">
        口径：首屏 = adapter 取数 + analyzeGanttDataset + 两次 rAF（绘制提交）合计；重复 {{ PROBE_ROUNDS }}
        次取 P95。滚动 =
        .gantt-scroll 从 0 到 scrollHeight 等步长推进 120 帧，统计相邻帧间隔。数值是本机实测，
        与机器性能相关；同一浏览器重复运行即可复现。
      </p>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header
        >订单表（{{ analysis?.summary.rowCount ?? 0 }} 行，C29 排序 orderNo）</template
      >
      <el-table v-if="orderRowsView.length" :data="orderRowsView" size="small" border>
        <el-table-column prop="orderNo" label="订单号" width="140" fixed />
        <el-table-column prop="materialCode" label="物料" width="120" />
        <el-table-column prop="dueDate" label="交期" width="110" />
        <el-table-column label="延期" width="120">
          <template #default="{ row }">
            <span :class="{ 'is-danger': row.lateText !== '—' }">{{ row.lateText }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="priorityLabel" label="优先级" width="86" />
        <el-table-column prop="freezeStatus" label="冻结" width="96" />
        <el-table-column prop="taskCount" label="任务数" width="86" />
        <el-table-column label="冲突" width="80">
          <template #default="{ row }">
            <span :class="{ 'is-danger': row.conflictCount > 0 }">{{ row.conflictCount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="resourceCodes" label="涉及资源" min-width="220" />
        <el-table-column label="选中任务" width="110">
          <template #default>
            <span class="muted">点击甘特条</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无订单数据" />
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header>
        人工调整（C22 / C36 POST /api/v1/tasks/{id}/adjust）
        <span class="muted">（先在上方甘特图中点选任务条）</span>
      </template>
      <el-empty
        v-if="!selectedBar || !selectedRow"
        description="未选中任务：点击订单甘特任务条后开始调整"
      />
      <div v-else>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="订单 / 工序">
            {{ selectedRow.key }} / #{{ selectedBar.operationIndex }}
            {{ selectedBar.operationName ?? '' }}
          </el-descriptions-item>
          <el-descriptions-item label="任务 / 版本">
            #{{ selectedBar.taskId }} / v{{ selectedBar.version }} / qty
            {{ selectedBar.qty ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="资源">
            {{ selectedBar.resourceCode ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="当前时间">
            {{ localInput(selectedBar.startTime) }} → {{ localInput(selectedBar.endTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ getEnumLabel('taskStatus', selectedBar.status) }}（{{ selectedBar.status }}）
          </el-descriptions-item>
          <el-descriptions-item label="lockType">{{ selectedLockType }}</el-descriptions-item>
          <el-descriptions-item label="订单级 freeze_status">
            {{ selectedRow.freezeStatus ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="任务级冻结">
            {{ selectedTaskFrozen ? 'FROZEN' : '否' }}
          </el-descriptions-item>
          <el-descriptions-item label="生效（三层取交集）">
            <span :class="{ 'is-danger': selectedEffectiveFrozen }">{{
              selectedEffectiveFrozen ? 'FROZEN（不可放宽）' : selectedLockType
            }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="selectedEffectiveFrozen"
          class="adjust-banner"
          type="warning"
          :closable="false"
          show-icon
          title="三层冻结取交集：订单级与任务级任一 FROZEN，MOVE / LOCK / UNFREEZE / SPLIT / MERGE 都会被 42201 拒绝"
        />
        <el-alert
          v-else-if="selectedLockType !== 'NONE'"
          class="adjust-banner"
          type="info"
          :closable="false"
          show-icon
          :title="`锁优先级 FROZEN > lockType > frozen：当前 lockType=${selectedLockType}，放宽请求仍受锁窗约束`"
        />

        <el-form label-width="110px" class="adjust-form" size="small">
          <el-form-item label="操作">
            <el-radio-group v-model="adjOperation">
              <el-radio-button
                v-for="option in adjustOperations"
                :key="option.value"
                :value="option.value"
                >{{ option.label }}</el-radio-button
              >
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="adjOperation === 'MOVE'" label="目标资源">
            <el-select v-model="adjResourceCode" style="width: 200px">
              <el-option
                v-for="option in resourceOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-if="adjOperation === 'MOVE'" label="目标开始时间">
            <el-date-picker
              v-model="adjStartTime"
              type="datetime"
              format="YYYY-MM-DD HH:mm"
              value-format="YYYY-MM-DD HH:mm:ss"
              style="width: 220px"
            />
            <span class="muted adjust-inline">{{ movePreview }}</span>
          </el-form-item>
          <el-form-item v-if="adjOperation === 'LOCK'" label="锁定窗口">
            <el-date-picker
              v-model="adjLockFrom"
              type="datetime"
              format="YYYY-MM-DD HH:mm"
              value-format="YYYY-MM-DD HH:mm:ss"
              style="width: 200px"
            />
            <span class="adjust-inline">→</span>
            <el-date-picker
              v-model="adjLockTo"
              type="datetime"
              format="YYYY-MM-DD HH:mm"
              value-format="YYYY-MM-DD HH:mm:ss"
              style="width: 200px"
            />
            <span class="muted adjust-inline">
              闭区间 [from,to]，to 必须晚于 from；零长度窗口会被 DB CHECK 拒绝（HTTP 400 /
              40000），前端先拦
            </span>
          </el-form-item>
          <el-form-item v-if="adjOperation === 'SPLIT'" label="拆分数量">
            <el-input
              v-model="adjSplitQuantities"
              placeholder="如 10, 20, 30"
              style="max-width: 260px"
            />
            <span class="muted adjust-inline">
              2–10 段，每段 > 0，和必须等于 qty {{ selectedBar.qty ?? '—' }}（容差 1e-4）；源任务保留第
              1 段
            </span>
          </el-form-item>
          <el-form-item v-if="adjOperation === 'MERGE'" label="合并任务">
            <el-select
              v-model="adjMergeTaskIds"
              multiple
              collapse-tags
              placeholder="选择同工序同资源的相邻任务（当前任务自动加入）"
              style="min-width: 320px"
            >
              <el-option
                v-for="option in mergeCandidates"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <span class="muted adjust-inline">
              当前任务 #{{ selectedBar.taskId }} 自动加入 mergeTaskIds；需 2–10 项且在资源内连续相邻
            </span>
          </el-form-item>
          <el-form-item label="原因 reason">
            <el-input
              v-model="adjReason"
              maxlength="512"
              show-word-limit
              placeholder="FREEZE / SPLIT / MERGE 必填；MOVE/LOCK/UNFREEZE 可选（写入 adjustment 审计）"
              style="max-width: 520px"
            />
          </el-form-item>
          <el-form-item label="dryRun">
            <el-switch v-model="adjDryRun" />
            <span class="muted adjust-inline">
              只校验不落库：返回 applied=true 且 adjustmentId=null；真实提交时本页先自动跑一次 dryRun
              预校验，失败则不写库并用快照回滚本地视图
            </span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="adjusting" @click="submitAdjust(adjOperation)"
              >提交 {{ adjOperation }}</el-button
            >
            <el-button
              v-if="lastAdjustmentId && dataSource === 'api'"
              :loading="adjusting"
              @click="undoLastAdjustment"
              >撤销本次（{{ lastAdjustmentId }}）</el-button
            >
            <el-button :disabled="adjusting" @click="resetAdjustForm">重置</el-button>
            <span class="muted adjust-inline">
              Idempotency-Key 前缀 gantt-pre / gantt-adj / gantt-undo；同 payload 失败重试复用原 key
            </span>
          </el-form-item>
        </el-form>

        <el-alert
          v-if="adjustFeedback"
          class="adjust-feedback"
          :type="adjustFeedback.level"
          :closable="false"
          show-icon
        >
          <template #title>
            {{ adjustFeedback.title }}
            <span class="muted"
              >（HTTP {{ adjustFeedback.httpStatus ?? '—' }} / code
              {{ adjustFeedback.code ?? '—' }}）</span
            >
          </template>
          <pre class="adjust-detail">{{ adjustFeedback.detail }}</pre>
        </el-alert>
      </div>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header>
        任务落在资源非工作时段（订单视图 C14 校验）
        <span class="muted">（{{ calendarChecks.length }} 个任务，前 50 条）</span>
      </template>
      <el-table
        v-if="calendarChecks.length"
        :data="calendarChecks.slice(0, 50)"
        size="small"
        border
      >
        <el-table-column prop="taskId" label="任务" width="100" />
        <el-table-column prop="orderNo" label="订单" width="140" />
        <el-table-column prop="resourceCode" label="资源" width="130" />
        <el-table-column prop="totalMinutes" label="占用分钟" width="100" />
        <el-table-column prop="offCalendarMinutes" label="非工时分钟" width="110" />
        <el-table-column prop="message" label="说明（与资源日历同源）" min-width="320" />
      </el-table>
      <el-empty v-else description="所有任务都落在其资源日历工作时段内，或资源未绑日历无法校验" />
      <p class="perf-note">
        校验口径：C14 日历的班次工作片段（含跨夜班次当日部分、非排班日、非工作例外日）； 资源未绑 /
        缺失 / 失败时不做假定校验，页面在日历绑定指标中显式标注。
      </p>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header>
        资源日历（C14）· 已绑定 {{ analysis?.calendarSummary.boundCount ?? 0 }} /
        {{ analysis?.summary.resourceCount ?? 0 }}
      </template>
      <el-table v-if="calendarRowsView.length" :data="calendarRowsView" size="small" border>
        <el-table-column prop="rowKey" label="资源" width="150" />
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <el-tag size="small" :type="calendarStateTag(row.state)" effect="plain">
              {{ row.state }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="calendarId" width="140">
          <template #default="{ row }">{{ row.calendarId ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="日历" width="150">
          <template #default="{ row }">{{ row.calendarName ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="shifts" label="班次（C14）" min-width="220" />
        <el-table-column label="例外日 / 覆盖" min-width="260">
          <template #default="{ row }">
            {{ row.holidays }}
            <br />
            <span class="muted"
              >覆盖 {{ row.coveredDays }} 天 · 未覆盖 {{ row.uncoveredDays }} 天 · 调休推定
              {{ row.makeupAssumptionDays }} 天</span
            >
          </template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="200" />
      </el-table>
      <el-empty v-else description="尚无资源日历绑定数据" />
      <p class="perf-note">
        订单行没有单一日历，故日历表仍按资源编码展示；{{ GANTT_CALENDAR_TIMEZONE_NOTE }}。
      </p>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header>
        日历停机窗口（前 20 条 / 共 {{ analysis?.calendarSummary.windowCount ?? 0 }} 条）
      </template>
      <el-table v-if="calendarWindowRows.length" :data="calendarWindowRows" size="small" border>
        <el-table-column prop="rowKey" label="资源" width="130" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="
                row.kind === 'HOLIDAY' ? 'danger' : row.kind === 'NON_WORKDAY' ? 'info' : 'primary'
              "
              effect="plain"
            >
              {{ calendarWindowKindLabel(row.kind) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开始" width="130">
          <template #default="{ row }">{{ formatWindowTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="结束" width="130">
          <template #default="{ row }">{{ formatWindowTime(row.endTime) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="日历依据（可读）" min-width="280" />
      </el-table>
      <el-empty v-else description="没有可用日历停机窗口（资源未绑日历，或窗口全部落在工作时段）" />
      <p class="perf-note">
        停机窗口按资源保留，不按订单行叠加：订单跨多资源，叠加会把「资源停机」误读为「订单停机」。
      </p>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header> 冲突清单（共 {{ conflictRows.length }} 条，展示前 50 条） </template>
      <el-table v-if="conflictRows.length" :data="conflictRows.slice(0, 50)" size="small" border>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="conflictTagType(row.type)" effect="plain">
              {{ conflictTypeLabel(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="任务" width="140">
          <template #default="{ row }">#{{ row.taskIds[0] }} / #{{ row.taskIds[1] }}</template>
        </el-table-column>
        <el-table-column prop="resourceCode" label="资源" width="130" />
        <el-table-column prop="overlapMinutes" label="重叠分钟" width="100" />
        <el-table-column prop="message" label="原因（可读）" min-width="360" />
      </el-table>
      <el-empty v-else description="当前订单集合没有冲突" />
      <p class="perf-note">
        订单视图的冲突按任务真实 resourceCode 分组（不是按订单行），与资源视图同一套判定；
        依赖按订单工序链（operationIndex）串联。
      </p>
    </el-card>

  </div>
</template>

<style scoped>
.empty-hint {
  margin: 0 0 var(--space-3);
  color: var(--text-2);
  font-size: var(--font-sm);
}

/* 甘特页顶部压缩：减少卡体内边距、表单行高与图例行距，让时间轴更早进入首屏 */
.gantt-page :deep(.page-title) {
  margin-bottom: var(--space-2);
  font-size: calc(var(--font-title) - 2px);
}

.gantt-page :deep(.el-card__header) {
  padding: var(--space-2) var(--space-3);
}

.gantt-page :deep(.el-card__body) {
  padding: var(--space-3);
}

.gantt-toolbar-card :deep(.el-card__body) {
  padding: var(--space-2) var(--space-3);
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.toolbar__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding-top: 0;
}

/* 当前数据源必须一眼可辨：真实后端=绿色，合成数据=橙色，字号不低于 --font-sm。 */
.source-badge {
  font-size: var(--font-sm);
  font-weight: 700;
}

.toolbar :deep(.el-form-item) {
  margin-bottom: var(--space-1);
  margin-right: var(--space-3);
}

.toolbar :deep(.el-checkbox) {
  margin-right: var(--space-2);
}

.legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-1) var(--space-3);
  margin: var(--space-1) 0 0;
  font-size: var(--font-xs);
  line-height: 16px;
  color: var(--text-2);
}

.legend__title {
  font-weight: 600;
}

.legend__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend__dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 3px;
}

.legend__dot.tag-primary {
  background: var(--primary);
}

.legend__dot.tag-success {
  background: var(--ok);
}

.legend__dot.tag-warning {
  background: var(--warn);
}

.legend__dot.tag-info {
  background: var(--text-3);
}

.legend__setup {
  --setup-a: color-mix(in srgb, var(--text-3) 55%, var(--card));
  --setup-b: var(--border);
  display: inline-block;
  width: 18px;
  height: 6px;
  border-radius: 2px;
  background: repeating-linear-gradient(
    -45deg,
    var(--setup-a),
    var(--setup-a) 3px,
    var(--setup-b) 3px,
    var(--setup-b) 6px
  );
}

.legend__frozen {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 14px;
  border: 1px dashed var(--text-2);
  border-radius: 2px;
  background: linear-gradient(
    180deg,
    color-mix(in srgb, var(--text-3) 55%, var(--card)),
    var(--text-3)
  );
  color: var(--text);
  font-size: var(--font-2xs);
  font-style: normal;
}

.legend__lock {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 14px;
  border: 1px solid var(--warn);
  border-radius: 2px;
  background: var(--primary);
  color: var(--text);
  font-size: var(--font-2xs);
  font-style: normal;
}

.legend__conflict {
  display: inline-block;
  width: 16px;
  height: 12px;
  border: 1px solid var(--danger);
  border-radius: 2px;
  background: var(--primary);
}

.legend__calendar {
  display: inline-block;
  width: 18px;
  height: 12px;
  border-radius: 2px;
  background: linear-gradient(
    90deg,
    color-mix(in srgb, var(--text-3) 45%, transparent) 0 6px,
    color-mix(in srgb, var(--primary) 30%, transparent) 6px 12px,
    color-mix(in srgb, var(--danger) 35%, transparent) 12px 18px
  );
}

.legend__due {
  display: inline-block;
  width: 2px;
  height: 14px;
  border-left: 1px dashed var(--warn);
}

.scope-note,
.perf-note {
  margin: var(--space-2) 0 0;
  font-size: var(--font-xs);
  line-height: 18px;
  color: var(--text-3);
}

.perf-probe {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.perf-probe-result {
  margin-top: var(--space-3);
}

.is-danger {
  color: var(--danger);
}

.muted {
  color: var(--text-3);
  font-size: var(--font-xs);
}

.issue-list {
  margin: var(--space-1) 0 0;
  padding-left: 18px;
  font-size: var(--font-xs);
  line-height: 18px;
}

.stage {
  min-height: 120px;
}

.adjust-form {
  margin-top: var(--space-3);
}

.adjust-banner,
.adjust-feedback {
  margin-top: var(--space-3);
}

.adjust-inline {
  margin-left: var(--space-2);
  line-height: 24px;
}

.adjust-detail {
  margin: 6px 0 0;
  font-family: inherit;
  font-size: var(--font-xs);
  line-height: 18px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
