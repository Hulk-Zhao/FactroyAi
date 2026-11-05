<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import {
  createGanttAdapter,
  getGanttDatasetProfile,
  getMockQueryRange,
  GANTT_DATASET_PROFILES
} from '@/components/gantt/adapters'
import { analyzeGanttDataset } from '@/components/gantt/analysis'
import type { GanttAnalysis, GanttConflict, GanttLayerFlags } from '@/components/gantt/analysis'
import { PageSkeleton } from '@/components/common'
import { GANTT_CALENDAR_TIMEZONE_NOTE } from '@/components/gantt/calendar'
import type { GanttCalendarRowState, GanttCalendarWindowKind } from '@/components/gantt/calendar'
import GanttTimeline from '@/components/gantt/GanttTimeline.vue'
import type { GanttDataset, GanttZoom } from '@/components/gantt'
import type { GanttDatasetKey } from '@/components/gantt/adapters'
import { DEFAULT_SANDBOX_ID } from '@/constants/defaults'
import type { Calendar } from '@/types/calendars'

interface RenderMetrics {
  loadMs: number
  renderMs: number
  totalMs: number
  domNodes: number
  measuredAt: string
}

const route = useRoute()

const zoomOptions: Array<{ value: GanttZoom; label: string }> = [
  { value: 'hour', label: '小时' },
  { value: 'day', label: '天' },
  { value: 'week', label: '周' },
  { value: 'month', label: '月' }
]

const datasetKey = ref<GanttDatasetKey>('48x8')
const zoom = ref<GanttZoom>('day')
/** 默认真实后端；Mock 只允许显式 ?source=mock 或工具栏显式切换进入。 */
const dataSource = ref<'mock' | 'api'>('api')
const layers = reactive<GanttLayerFlags>({
  calendar: true,
  idle: true,
  dependency: true,
  heat: false,
  conflict: true,
  freeze: true
})

const dataset = ref<GanttDataset | null>(null)
const analysis = ref<GanttAnalysis | null>(null)
const metrics = ref<RenderMetrics | null>(null)
const loading = ref(false)
const error = ref('')
const stageRef = ref<HTMLElement | null>(null)
let loadToken = 0

const adapter = computed(() =>
  createGanttAdapter({ dataset: datasetKey.value, source: dataSource.value })
)
const profile = computed(() => getGanttDatasetProfile(datasetKey.value))
const datasetIsEmpty = computed(() => dataset.value !== null && dataset.value.rows.length === 0)
const conflictRows = computed<GanttConflict[]>(() => analysis.value?.conflicts ?? [])
const conflictTypes = computed(() => new Set(conflictRows.value.map((item) => item.type)))
const peakLoadText = computed(() => {
  const ratio = analysis.value?.summary.peakLoadRatio
  return ratio === null || ratio === undefined ? '无（无可比日历）' : `${Math.round(ratio * 100)}%`
})

/** 资源编码 → 中文名；甘特行 key 为资源编码，label 为资源名称。 */
const resourceDisplayMap = computed(
  () => new Map((dataset.value?.rows ?? []).map((row) => [row.key, row.label]))
)

function resourceDisplay(code: string): string {
  const name = resourceDisplayMap.value.get(code)
  return name ? `${name}（${code}）` : code
}

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
    return '无'
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
  resourceName: string
  shifts: string
  holidays: string
}

const calendarRowsView = computed<CalendarRowView[]>(() => {
  const nameMap = new Map((dataset.value?.rows ?? []).map((row) => [row.key, row.label]))
  const bindingMap = new Map(
    (dataset.value?.calendarBindings ?? []).map((binding) => [binding.resourceCode, binding])
  )
  return (analysis.value?.calendarRows ?? []).map((state) => {
    const binding = bindingMap.get(state.rowKey)
    const calendar = binding?.calendar ?? null
    return {
      ...state,
      resourceName: nameMap.get(state.rowKey) ?? state.rowKey,
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

function calendarStateLabel(state: GanttCalendarRowState['state']): string {
  if (state === 'BOUND') {
    return '已绑定'
  }
  if (state === 'UNBOUND') {
    return '未绑定'
  }
  return state === 'MISSING' ? '日历缺失' : '读取失败'
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

function nextFrame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()))
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

async function loadDataset(): Promise<void> {
  const token = (loadToken += 1)
  loading.value = true
  error.value = ''
  dataset.value = null
  analysis.value = null
  metrics.value = null
  try {
    // 5000 容量档的任务会溢出默认 7 天窗口，Mock 下放宽到整档跨度，避免淡出过滤后不足 5000 条。
    const range =
      dataSource.value === 'mock' && datasetKey.value === '5000x50'
        ? {
            from: new Date(Date.UTC(2026, 10, 1)).toISOString(),
            to: new Date(Date.UTC(2026, 11, 31)).toISOString()
          }
        : getMockQueryRange()
    const startedAt = performance.now()
    const data = await adapter.value.load({
      sandboxId: DEFAULT_SANDBOX_ID,
      from: range.from,
      to: range.to
    })
    const afterLoad = performance.now()
    dataset.value = data
    analysis.value = analyzeGanttDataset(data)
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
      error.value = exception instanceof Error ? exception.message : '甘特数据加载失败'
      dataset.value = null
      analysis.value = null
    }
  } finally {
    if (token === loadToken) {
      loading.value = false
    }
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
  void loadDataset()
})

onMounted(() => {
  void loadDataset()
})
</script>

<template>
  <div class="gantt-page">
    <h2 class="page-title">资源甘特图（M2-FE-01 · 只读增强）</h2>

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
            <el-checkbox v-model="layers.calendar">日历停机</el-checkbox>
            <el-checkbox v-model="layers.idle">可插空档</el-checkbox>
            <el-checkbox v-model="layers.dependency">依赖</el-checkbox>
            <el-checkbox v-model="layers.heat">负载热力</el-checkbox>
            <el-checkbox v-model="layers.conflict">冲突高亮</el-checkbox>
            <el-checkbox v-model="layers.freeze">冻结标识</el-checkbox>
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
          <el-button size="small" :loading="loading" @click="loadDataset">重新加载</el-button>
        </div>
      </div>

      <div class="legend">
        <span class="legend__title">图层与标记：</span>
        <span class="legend__item"><i class="legend__conflict" />冲突（红色描边 + 原因）</span>
        <span class="legend__item"><i class="legend__calendar" />日历停机（C14 班次/例外日）</span>
        <span class="legend__item"
          ><i class="legend__idle" />可插空档（相邻任务 ≥30 分钟，推导）</span
        >
        <span class="legend__item"><i class="legend__link" />工序依赖 FS</span>
      </div>

    </el-card>

    <el-alert v-if="error" class="page-card" type="error" :closable="false" :title="error" />

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

    <el-card class="page-card" shadow="never">
      <template #header>
        只读甘特 · {{ profile.label }} · 缩放
        {{ zoomOptions.find((item) => item.value === zoom)?.label ?? zoom }}
      </template>
      <div ref="stageRef" v-loading="loading" class="stage">
        <PageSkeleton v-if="!dataset && loading" :rows="10" :show-title="false" />
        <el-empty
          v-else-if="datasetIsEmpty"
          description="该数据档在所选时间窗内没有任务（可能尚未排产）"
        >
          <template #default>
            <p class="empty-hint">
              先确认该方案已排产；仍为空时扩大时间窗或切换数据档后重新加载。
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
        />
        <el-empty v-else description="暂无甘特数据：请先加载排产结果，或检查筛选条件" />
      </div>
      <p class="perf-note">
        悬浮任务条可查看订单、加工窗口、换型窗口、辅资源、资源内序号与冲突原因；悬浮日历停机块可查看
        窗口类型与日历依据，悬浮热力块可查看「占用 / 日历工作分钟」分母。冻结任务以灰色 + 虚线 +
        「冻」标识， 明确不可拖拽（拖拽能力在 FE-03 落地前不存在）。
      </p>
      <p class="perf-note">
        口径：停机层只渲染 C14 日历事实（班次休息 / 非排班日 /
        非工作例外日），不再用相邻任务间隔推定停机； 热力分母为该资源的当日日历工作分钟，未绑 / 缺失
        / 失败的行显示「无」，不静默回落到 24h。 可插空档仍是相邻任务间隔 ≥30
        分钟的前端推导，仅作可插入候选，不代表真实停机指令。
      </p>
    </el-card>

    <p class="scope-note">
      M2-FE-01
      只读：时间轴缩放、状态配色、冲突高亮、负载热力、工序依赖、冻结标识。停机层与热力分母来自 C14
      资源工作日历（GET /resources 的 calendarId + GET /calendars/{id}）；C4 不返回日历字段。
      拖拽调整与撤销重做属 M2-FE-03（等待 W4 交付 /tasks/{id}/adjust），本页不提供任何写操作。
    </p>

    <el-card class="page-card" shadow="never">
      <template #header> 只读指标（{{ profile.label }}） </template>
      <el-descriptions v-if="dataset && analysis" :column="4" border size="small">
        <el-descriptions-item label="任务 / 资源行">
          {{ analysis.summary.taskCount }} / {{ analysis.summary.resourceCount }}
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
        <el-descriptions-item label="冻结任务">
          {{ analysis.summary.frozenTaskCount }}
        </el-descriptions-item>
        <el-descriptions-item label="资源空档合计（推导）">
          {{ Math.round(analysis.summary.idleMinutes / 60) }} 小时
        </el-descriptions-item>
        <el-descriptions-item label="峰值负载（日历工作分钟）">
          {{ peakLoadText }}
        </el-descriptions-item>
        <el-descriptions-item label="日历绑定">
          {{ analysis.calendarSummary.boundCount }} / {{ analysis.summary.resourceCount }}
          <span class="muted"
            >（未绑 {{ analysis.calendarSummary.unboundCount }} · 缺失
            {{ analysis.calendarSummary.missingCount }} · 失败
            {{ analysis.calendarSummary.errorCount }}）</span
          >
        </el-descriptions-item>
        <el-descriptions-item label="日历停机窗口 / 覆盖天数">
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
        <el-descriptions-item label="DOM 节点数" :span="2">
          {{ metrics?.domNodes ?? '无' }}
        </el-descriptions-item>
        <el-descriptions-item label="测量时间" :span="2">
          {{ metrics?.measuredAt ?? '无' }}
        </el-descriptions-item>
      </el-descriptions>
      <el-empty v-else description="暂无甘特数据" />
      <p class="perf-note">
        口径：数据加载 → 两帧 requestAnimationFrame 后统计，包含日历解析（C14
        班次/例外日）、派生分析 （冲突 / 可插空档 / 热力 /
        依赖）与虚拟行渲染；时间轴切换不重复计算分析，仅重排绘制。
      </p>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header>
        资源日历（C14）· 已绑定
        {{ analysis?.calendarSummary.boundCount ?? 0 }} /
        {{ analysis?.summary.resourceCount ?? 0 }}
      </template>
      <el-table v-if="calendarRowsView.length" :data="calendarRowsView" size="small" border>
        <el-table-column label="资源" width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.resourceName }}</template>
        </el-table-column>
        <el-table-column label="绑定状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="calendarStateTag(row.state)" effect="plain">
              {{ calendarStateLabel(row.state) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="工作日历" width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.calendarName ?? '无' }}</template>
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
        数据流：C4 /gantt/resource 不返回 calendarId → GET /resources 取 calendarId → GET
        /calendars/{id} 取班次 / 例外日 / 生效期。{{ GANTT_CALENDAR_TIMEZONE_NOTE }}。 未绑 / 缺失 /
        失败的行不产生停机窗口与热力，页面显示「无」而非 24h 回落。
      </p>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header>
        日历停机窗口（前 20 条 / 共 {{ analysis?.calendarSummary.windowCount ?? 0 }} 条）
      </template>
      <el-table v-if="calendarWindowRows.length" :data="calendarWindowRows" size="small" border>
        <el-table-column label="资源" width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ resourceDisplay(row.rowKey) }}</template>
        </el-table-column>
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
      <el-empty
        v-else
        description="没有可用日历停机窗口（资源未绑日历，或所选窗口全部落在工作时段）"
      />
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header>
        冲突清单（共 {{ conflictRows.length }} 条，展示前 50 条）
        <el-tag
          v-if="conflictTypes.has('RESOURCE_OVERLAP')"
          size="small"
          type="warning"
          effect="plain"
        >
          资源重叠
        </el-tag>
        <el-tag
          v-if="conflictTypes.has('SECONDARY_RESOURCE_OVERLAP')"
          size="small"
          type="warning"
          effect="plain"
        >
          辅资源重叠
        </el-tag>
        <el-tag
          v-if="conflictTypes.has('DEPENDENCY_VIOLATION')"
          size="small"
          type="danger"
          effect="plain"
        >
          依赖违例
        </el-tag>
      </template>
      <el-table v-if="conflictRows.length" :data="conflictRows.slice(0, 50)" size="small" border>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="conflictTagType(row.type)" effect="plain">
              {{ conflictTypeLabel(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="任务" width="140">
          <template #default="{ row }"> #{{ row.taskIds[0] }} / #{{ row.taskIds[1] }} </template>
        </el-table-column>
        <el-table-column label="资源" width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ resourceDisplay(row.resourceCode) }}</template>
        </el-table-column>
        <el-table-column prop="overlapMinutes" label="重叠分钟" width="100" />
        <el-table-column prop="message" label="原因（可读）" min-width="360" />
      </el-table>
      <el-empty v-else description="当前数据集没有冲突" />
      <p class="perf-note">
        冲突为前端按 C4 任务条推导：同资源（含换型窗口）时段重叠、辅资源共用重叠、同订单工序 FS
        顺推违例。契约当前不返回冲突对象，故不做持久化认领。
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

.legend__conflict {
  display: inline-block;
  width: 16px;
  height: 12px;
  border: 1px solid var(--danger);
  border-radius: 2px;
  background: var(--primary);
}

.legend__idle {
  display: inline-block;
  width: 18px;
  height: 12px;
  border-radius: 2px;
  background: repeating-linear-gradient(
    -45deg,
    color-mix(in srgb, var(--text-3) 30%, transparent),
    color-mix(in srgb, var(--text-3) 30%, transparent) 3px,
    color-mix(in srgb, var(--text-3) 8%, transparent) 3px,
    color-mix(in srgb, var(--text-3) 8%, transparent) 6px
  );
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

.legend__link {
  display: inline-block;
  width: 22px;
  height: 2px;
  background: var(--primary);
}

.scope-note,
.perf-note {
  margin: var(--space-2) 0 0;
  font-size: var(--font-xs);
  line-height: 18px;
  color: var(--text-3);
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
</style>
