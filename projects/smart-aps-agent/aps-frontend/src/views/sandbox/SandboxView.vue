<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'

import { ApiError, createIdempotencyKey } from '@/api/http'
import { applySandbox, compareSandbox, createSandbox, listSandboxes } from '@/api/orders'
import { whatIf } from '@/api/solve'
import { DataTable } from '@/components/common'
import { DEFAULT_SANDBOX_ID } from '@/constants/defaults'
import { clearHelpContext, setHelpContext } from '@/constants/help/context'
import { useTraceStore } from '@/stores/trace'
import type { Sandbox, SandboxApplyResult, SandboxCompare, SandboxChangeType } from '@/types/orders'
import type { WhatIfResult } from '@/types/solve'

const traceStore = useTraceStore()

/** 用户开始在「问一问」卡片里操作时，帮助面板切到 What-if 场景。 */
function syncWhatIfHelp(): void {
  setHelpContext('whatif')
}

onBeforeUnmount(() => {
  clearHelpContext('whatif')
})

const rows = ref<Sandbox[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const typeFilter = ref('')

const createVisible = ref(false)
const creating = ref(false)
const createError = ref('')
const createForm = reactive({ name: '', expiresAt: '' })

const applyVisible = ref(false)
const applying = ref(false)
const applyError = ref('')
const applyTarget = ref<Sandbox | null>(null)
const applyReason = ref('')
const applyResult = ref<SandboxApplyResult | null>(null)

const compareLoading = ref(false)
const compareError = ref('')
const compareResult = ref<SandboxCompare | null>(null)
const compareSourceId = ref('')
const compareTargetId = ref('')

const baseline = computed(() => rows.value.find((item) => item.type === 'BASELINE') ?? null)
const whatIfOptions = computed(() => rows.value.filter((item) => item.type === 'WHAT_IF'))

const askSandboxId = ref('')
const askQuestion = ref('')
const askOrderNos = ref('')
const askLoading = ref(false)
const askResult = ref<WhatIfResult | null>(null)
const askError = ref('')
const askMessage = ref('')
const askCode = ref<number | null>(null)
const askHttpStatus = ref<number | null>(null)
const askTraceId = ref('')

/** 只做参数白名单拼装：orderNos 显式解析，其余未识别参数不发给后端。 */
async function submitAsk(): Promise<void> {
  askError.value = ''
  askMessage.value = ''
  askCode.value = null
  askHttpStatus.value = null
  askResult.value = null
  askTraceId.value = ''
  if (!askSandboxId.value) {
    askError.value = '请选择场景'
    return
  }
  if (!askQuestion.value.trim()) {
    askError.value = '请填写问题'
    return
  }
  const orderNos = askOrderNos.value
    .split(/[,，、\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
  const parameters: Record<string, unknown> = orderNos.length ? { orderNos } : {}
  askLoading.value = true
  try {
    askResult.value = await whatIf(
      {
        sandboxId: askSandboxId.value,
        question: askQuestion.value.trim(),
        parameters
      },
      createIdempotencyKey()
    )
    askTraceId.value = traceStore.latestTraceId
  } catch (exception) {
    if (exception instanceof ApiError) {
      // 422 的服务端 message 已经点名不支持的参数，原样展示，不改写。
      askMessage.value = exception.message
      askCode.value = exception.code
      askHttpStatus.value = exception.httpStatus ?? null
      askTraceId.value = exception.traceId || traceStore.latestTraceId
    } else {
      askError.value = exception instanceof Error ? exception.message : '问一问请求失败'
      askTraceId.value = traceStore.latestTraceId
    }
  } finally {
    askLoading.value = false
  }
}

function formatTime(value?: string | null): string {
  if (!value) {
    return '无'
  }
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value
}

function sandboxTypeLabel(type: Sandbox['type']): string {
  if (type === 'BASELINE') {
    return '主计划'
  }
  return type === 'WHAT_IF' ? 'What-If 方案' : '已归档'
}

function sandboxNameOf(id?: string | null): string {
  if (!id) {
    return '无'
  }
  const found = rows.value.find((item) => item.id === id)
  return found ? found.name : '场景名称待接入'
}

function signedMinutes(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value} min`
}

function utilizationText(value: number): string {
  return value.toFixed(3)
}

function deltaClass(value: number): string {
  if (value > 0) {
    return 'delta-negative'
  }
  if (value < 0) {
    return 'delta-positive'
  }
  return 'delta-neutral'
}

function changeTypeLabel(type: SandboxChangeType): string {
  return { ADDED: '新增', REMOVED: '移除', MOVED: '移动' }[type]
}

async function loadSandboxes() {
  loading.value = true
  error.value = ''
  try {
    const result = await listSandboxes({
      page: page.value,
      size: size.value,
      type: typeFilter.value || undefined
    })
    rows.value = result.items
    total.value = result.total
    page.value = result.page
    size.value = result.size
    if (!compareTargetId.value) {
      compareTargetId.value = baseline.value?.id ?? ''
    }
    if (!askSandboxId.value) {
      askSandboxId.value = whatIfOptions.value[0]?.id ?? ''
    }
  } catch (exception) {
    rows.value = []
    total.value = 0
    error.value = exception instanceof Error ? exception.message : '场景列表加载失败'
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void loadSandboxes()
}

function openCreate() {
  createForm.name = ''
  createForm.expiresAt = ''
  createError.value = ''
  createVisible.value = true
}

async function submitCreate() {
  createError.value = ''
  if (!createForm.name.trim()) {
    createError.value = '场景名称不能为空'
    return
  }
  creating.value = true
  try {
    // C32：创建只允许 WHAT_IF；父场景缺省为主线 BASELINE。
    await createSandbox(
      {
        name: createForm.name.trim(),
        type: 'WHAT_IF',
        parentSandboxId: DEFAULT_SANDBOX_ID,
        expiresAt: createForm.expiresAt || null
      },
      createIdempotencyKey()
    )
    ElMessage.success('WHAT_IF 场景已创建')
    createVisible.value = false
    await loadSandboxes()
  } catch (exception) {
    createError.value = exception instanceof Error ? exception.message : '创建场景失败'
  } finally {
    creating.value = false
  }
}

function openApply(row: Sandbox) {
  applyTarget.value = row
  applyReason.value = ''
  applyError.value = ''
  applyResult.value = null
  applyVisible.value = true
}

async function submitApply() {
  if (!applyTarget.value) {
    return
  }
  applyError.value = ''
  if (!applyReason.value.trim()) {
    applyError.value = '请填写应用原因（后端会写入审计）'
    return
  }
  applying.value = true
  try {
    applyResult.value = await applySandbox(
      applyTarget.value.id,
      applyReason.value.trim(),
      createIdempotencyKey()
    )
    ElMessage.success('场景已提升为 BASELINE，并发布新计划版本')
    await loadSandboxes()
  } catch (exception) {
    applyError.value =
      exception instanceof ApiError && exception.code === 42200
        ? exception.message
        : exception instanceof Error
          ? exception.message
          : '应用场景失败'
  } finally {
    applying.value = false
  }
}

async function runCompare(sourceId: string, targetId: string) {
  compareError.value = ''
  compareResult.value = null
  if (!sourceId || !targetId) {
    compareError.value = '请选择要对比的场景与目标场景'
    return
  }
  if (sourceId === targetId) {
    compareError.value = '源场景与目标场景不能相同'
    return
  }
  compareLoading.value = true
  try {
    compareResult.value = await compareSandbox(sourceId, targetId)
  } catch (exception) {
    compareError.value = exception instanceof Error ? exception.message : '场景对比失败'
  } finally {
    compareLoading.value = false
  }
}

onMounted(() => {
  void loadSandboxes()
})

function compareWithBaseline(row: Sandbox) {
  if (!baseline.value) {
    ElMessage.warning('当前列表没有 BASELINE 场景可作为对比目标')
    return
  }
  compareSourceId.value = row.id
  compareTargetId.value = baseline.value.id
  void runCompare(row.id, baseline.value.id)
}
</script>

<template>
  <div>
    <h2 class="page-title">场景沙箱</h2>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="hint-alert"
      title="新建仅支持 What-If 场景；应用场景时会单事务发布新版本，并把该场景提升为主计划"
      description="对比方向固定为「当前场景 − 目标场景」；交期差以分钟计、可为负；利用率差为 0–1 的小数并保留 3 位。"
    />

    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-header">
          <span>场景列表</span>
          <span class="muted">已归档场景不在列表返回范围内</span>
        </div>
      </template>
      <div class="toolbar-row">
        <el-select v-model="typeFilter" clearable placeholder="全部类型" style="width: 180px">
          <el-option label="主计划" value="BASELINE" />
          <el-option label="What-If 方案" value="WHAT_IF" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="openCreate">新建 What-If 场景</el-button>
      </div>

      <DataTable
        v-model:page="page"
        v-model:size="size"
        :data="rows"
        :loading="loading"
        :error="error"
        :total="total"
        row-key="id"
        empty-text="暂无场景"
        @change="loadSandboxes"
        @retry="loadSandboxes"
      >
        <el-table-column prop="name" label="场景名称" min-width="180" />
        <el-table-column label="场景类型" width="130">
          <template #default="{ row }">
            <el-tag :type="row.type === 'BASELINE' ? 'success' : 'warning'" size="small">
              {{ sandboxTypeLabel(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="父场景" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">{{ sandboxNameOf(row.parentSandboxId) }}</template>
        </el-table-column>
        <el-table-column label="过期时间" min-width="170">
          <template #default="{ row }">{{ formatTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="场景版本" width="100">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="row.type === 'WHAT_IF'">
              <el-button link type="primary" @click="compareWithBaseline(row)">对比基线</el-button>
              <el-button link type="warning" @click="openApply(row)">应用场景</el-button>
            </template>
          </template>
        </el-table-column>
      </DataTable>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-header">
          <span>场景对比</span>
          <span class="muted">对比当前场景与目标场景的排产差异</span>
        </div>
      </template>
      <div class="compare-form">
        <div class="filter-field">
          <label class="field-label" for="compare-source">源场景（What-If）</label>
          <el-select id="compare-source" v-model="compareSourceId" clearable placeholder="选择源场景" style="width: 100%">
            <el-option
              v-for="item in whatIfOptions"
              :key="item.id"
              :label="`${item.name}（v${item.version}）`"
              :value="item.id"
            />
          </el-select>
        </div>
        <div class="filter-field">
          <label class="field-label" for="compare-target">目标场景</label>
          <el-select id="compare-target" v-model="compareTargetId" clearable placeholder="选择目标场景" style="width: 100%">
            <el-option
              v-for="item in rows"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </div>
        <el-button
          type="primary"
          :loading="compareLoading"
          @click="runCompare(compareSourceId, compareTargetId)"
        >
          对比
        </el-button>
      </div>

      <el-alert v-if="compareError" type="error" :closable="false" :title="compareError" class="inline-alert" />

      <template v-if="compareResult">
        <el-alert
          v-if="compareResult.truncated"
          type="warning"
          :closable="false"
          show-icon
          class="inline-alert"
          title="结果被截断：下方明细不是全量"
          :description="`计数仍为全量真实值（新增 ${compareResult.addedTaskCount} / 移除 ${compareResult.removedTaskCount} / 移动 ${compareResult.movedTaskCount}），明细仅展示已返回的 ${compareResult.changedTasks.length} 条。`"
        />

        <div class="summary-grid">
          <div class="summary-card">
            <p class="summary-label">新增任务</p>
            <p class="summary-value">{{ compareResult.addedTaskCount }}</p>
          </div>
          <div class="summary-card">
            <p class="summary-label">移除任务</p>
            <p class="summary-value">{{ compareResult.removedTaskCount }}</p>
          </div>
          <div class="summary-card">
            <p class="summary-label">移动任务</p>
            <p class="summary-value">{{ compareResult.movedTaskCount }}</p>
          </div>
          <div class="summary-card">
            <p class="summary-label">交期差（分钟，正=更晚）</p>
            <p class="summary-value" :class="deltaClass(compareResult.dueDateDeltaMinutes)">
              {{ signedMinutes(compareResult.dueDateDeltaMinutes) }}
            </p>
          </div>
          <div class="summary-card">
            <p class="summary-label">产能利用率差（0–1）</p>
            <p class="summary-value" :class="deltaClass(compareResult.utilizationDelta)">
              {{ utilizationText(compareResult.utilizationDelta) }}
            </p>
          </div>
          <div class="summary-card">
            <p class="summary-label">换型次数差</p>
            <p class="summary-value" :class="deltaClass(compareResult.setupCountDelta)">
              {{ compareResult.setupCountDelta > 0 ? '+' : '' }}{{ compareResult.setupCountDelta }}
            </p>
          </div>
        </div>

        <p class="direction-hint">
          对比方向：{{ sandboxNameOf(compareResult.sandboxId) }} −
          {{ sandboxNameOf(compareResult.targetId) }}
        </p>

        <DataTable :data="compareResult.changedTasks" :show-pagination="false" row-key="taskId">
          <el-table-column prop="taskId" label="任务 ID" width="110" />
          <el-table-column prop="orderNo" label="订单号" min-width="160" />
          <el-table-column prop="resourceCode" label="资源编码" min-width="160" />
          <el-table-column label="变更类型" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.changeType === 'ADDED' ? 'success' : row.changeType === 'REMOVED' ? 'danger' : 'warning'">
                {{ changeTypeLabel(row.changeType) }}
              </el-tag>
            </template>
          </el-table-column>
        </DataTable>
      </template>
    </el-card>

    <el-card
      shadow="never"
      class="section"
      @focusin="syncWhatIfHelp"
      @click="syncWhatIfHelp"
    >
      <template #header>
        <div class="card-header">
          <span>场景问答（What-If）</span>
          <span class="muted">只读推演，不改正式计划</span>
        </div>
      </template>

      <div class="ask-form">
        <div class="filter-field">
          <label class="field-label" for="ask-sandbox">场景</label>
          <el-select
            id="ask-sandbox"
            v-model="askSandboxId"
            placeholder="选择场景（仅 What-If 可用）"
            style="width: 100%"
          >
            <el-option
              v-for="item in rows"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </div>
        <div class="filter-field">
          <label class="field-label" for="ask-order-nos">订单号（可空，逗号或空格分隔）</label>
          <el-input
            id="ask-order-nos"
            v-model="askOrderNos"
            clearable
            placeholder="如 SO-20261105-001, SO-20261105-002"
          />
        </div>
        <div class="filter-field ask-question">
          <label class="field-label" for="ask-question">问题</label>
          <el-input
            id="ask-question"
            v-model="askQuestion"
            type="textarea"
            :rows="2"
            maxlength="2000"
            show-word-limit
            placeholder="仅用于回显与审计，不参与语义解析；结果来自真实沙箱推演"
          />
        </div>
        <div class="ask-actions">
          <el-button type="primary" :loading="askLoading" @click="submitAsk">问一问</el-button>
        </div>
      </div>

      <p class="direction-hint">
        参数走服务端白名单；传主计划场景会被服务端拒绝（422/42200），错误信息原样展示。
      </p>

      <el-alert
        v-if="askError"
        type="error"
        :closable="false"
        :title="askError"
        class="inline-alert"
      />

      <el-alert
        v-if="askMessage"
        type="error"
        :closable="false"
        class="inline-alert"
        :title="askMessage"
      >
        <template #default>
          <p class="ask-error-meta">
            状态码 {{ askHttpStatus ?? '无' }} · 业务码 {{ askCode ?? '无' }}
            <span v-if="askTraceId"> · 追踪号 {{ askTraceId }}</span>
          </p>
        </template>
      </el-alert>

      <template v-if="askResult">
        <div class="ask-answer">
          <p class="ask-answer-label">回答</p>
          <p class="ask-answer-text">{{ askResult.answer }}</p>
        </div>

        <el-alert
          v-if="askResult.available === false"
          type="info"
          :closable="false"
          show-icon
          class="inline-alert"
          title="推演未产生可比结果"
          :description="askResult.unavailableReason || '服务端未返回对比结果'"
        />

        <el-alert
          v-if="askResult.comparisonWarnings?.length"
          type="warning"
          :closable="false"
          show-icon
          class="inline-alert"
          title="基线可比性警告（差值需结合上下文解读）"
          :description="askResult.comparisonWarnings.join(' / ')"
        />

        <div v-if="askResult.comparison" class="summary-grid">
          <div class="summary-card">
            <p class="summary-label">新增任务</p>
            <p class="summary-value">{{ askResult.comparison.addedTaskCount }}</p>
          </div>
          <div class="summary-card">
            <p class="summary-label">移出任务</p>
            <p class="summary-value">{{ askResult.comparison.removedTaskCount }}</p>
          </div>
          <div class="summary-card">
            <p class="summary-label">移动任务</p>
            <p class="summary-value">{{ askResult.comparison.movedTaskCount }}</p>
          </div>
          <div class="summary-card">
            <p class="summary-label">交期差（分钟，正=更晚）</p>
            <p class="summary-value" :class="deltaClass(askResult.comparison.dueDateDeltaMinutes)">
              {{ signedMinutes(askResult.comparison.dueDateDeltaMinutes) }}
            </p>
          </div>
          <div class="summary-card">
            <p class="summary-label">产能利用率差（0–1）</p>
            <p class="summary-value" :class="deltaClass(askResult.comparison.utilizationDelta)">
              {{ utilizationText(askResult.comparison.utilizationDelta) }}
            </p>
          </div>
          <div class="summary-card">
            <p class="summary-label">换型次数差</p>
            <p class="summary-value" :class="deltaClass(askResult.comparison.setupCountDelta)">
              {{ askResult.comparison.setupCountDelta > 0 ? '+' : '' }}{{ askResult.comparison.setupCountDelta }}
            </p>
          </div>
        </div>

        <el-descriptions
          v-if="askResult.bottleneckChange"
          :column="2"
          border
          size="small"
          class="ask-bottleneck"
        >
          <el-descriptions-item label="瓶颈变化">
            <span v-if="askResult.bottleneckChange.comparable === false">不可比</span>
            <span v-else-if="askResult.bottleneckChange.changed">已变化</span>
            <span v-else>未变化</span>
          </el-descriptions-item>
          <el-descriptions-item label="前端瓶颈资源">
            <span v-if="askResult.bottleneckChange.before">
              {{ askResult.bottleneckChange.before.resourceCode ?? '无' }}（利用率
              {{ utilizationText(askResult.bottleneckChange.before.utilization ?? 0) }}）
            </span>
            <span v-else>无</span>
          </el-descriptions-item>
          <el-descriptions-item label="推演后瓶颈资源">
            <span v-if="askResult.bottleneckChange.after">
              {{ askResult.bottleneckChange.after.resourceCode ?? '无' }}（利用率
              {{ utilizationText(askResult.bottleneckChange.after.utilization ?? 0) }}）
            </span>
            <span v-else>无</span>
          </el-descriptions-item>
          <el-descriptions-item label="不可比原因">
            {{ askResult.bottleneckChange.notComparableReason || '无' }}
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="askResult.impactedOrders?.length" class="ask-impacted">
          <p class="field-label">
            受影响订单（{{ askResult.impactedOrders.length }} 个{{ askResult.impactedOrdersComplete === false ? '，明细不完整' : '' }}）
          </p>
          <p class="ask-impacted-list">{{ askResult.impactedOrders.join('、') }}</p>
        </div>

        <p class="direction-hint">
          <span v-if="askResult.source">来源：{{ askResult.source }} · </span>
          <span v-if="askResult.baselineTaskCount !== undefined">
            基线任务 {{ askResult.baselineTaskCount }} / 推演后 {{ askResult.afterTaskCount ?? '无' }} · 
          </span>
          <span v-if="askTraceId">追踪号：{{ askTraceId }}</span>
        </p>
      </template>
    </el-card>

    <el-dialog v-model="createVisible" title="新建 WHAT_IF 场景" width="520px" destroy-on-close>
      <el-alert v-if="createError" type="error" :closable="false" :title="createError" class="dialog-error" />
      <el-form label-position="top">
        <el-form-item label="场景名称">
          <el-input v-model="createForm.name" maxlength="80" />
        </el-form-item>
        <el-form-item label="过期时间（可空）">
          <el-date-picker
            v-model="createForm.expiresAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            placeholder="留空表示不过期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="类型">
          <el-input model-value="WHAT_IF（创建时固定）" disabled />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="applyVisible" title="应用场景（提升为 BASELINE）" width="560px" destroy-on-close>
      <el-alert v-if="applyError" type="error" :closable="false" :title="applyError" class="dialog-error" />
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="此操作会发布新计划版本，并将原 BASELINE 归档"
        class="dialog-error"
      />
      <el-form label-position="top">
        <el-form-item label="场景">
          <el-input :model-value="applyTarget?.name ?? ''" disabled />
        </el-form-item>
        <el-form-item label="应用原因 reason（必填，写入审计）">
          <el-input v-model="applyReason" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <el-descriptions v-if="applyResult" :column="2" border size="small" class="apply-result">
        <el-descriptions-item label="新版本号">v{{ applyResult.plan.version }}</el-descriptions-item>
        <el-descriptions-item label="受影响任务数">{{ applyResult.plan.affectedTaskCount }}</el-descriptions-item>
        <el-descriptions-item label="计划号">{{ applyResult.plan.planNo || '无' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ applyResult.plan.status }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="applyVisible = false">关闭</el-button>
        <el-button type="primary" :loading="applying" @click="submitApply">确认应用</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 var(--space-4);
  font-size: var(--font-title);
  color: var(--text);
}

.hint-alert,
.section {
  margin-bottom: var(--space-4);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.muted {
  color: var(--text-2);
  font-size: var(--font-xs);
}

.toolbar-row {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  margin-bottom: var(--space-3);
}

.compare-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr)) auto;
  gap: var(--space-3);
  align-items: end;
}

.ask-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
  align-items: end;
}

.ask-question {
  grid-column: 1 / -1;
}

.ask-actions {
  display: flex;
  justify-content: flex-end;
}

.ask-answer {
  margin-top: var(--space-4);
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  border: var(--border-width) solid var(--border);
}

.ask-answer-label {
  margin: 0 0 var(--space-2);
  font-size: var(--font-xs);
  letter-spacing: 0.04em;
  color: var(--text-2);
}

.ask-answer-text {
  margin: 0;
  font-size: var(--font-md);
  line-height: 1.6;
  color: var(--text);
  white-space: pre-wrap;
}

.ask-error-meta {
  margin: var(--space-2) 0 0;
  font-size: var(--font-xs);
  color: var(--text-2);
  word-break: break-all;
}

.ask-bottleneck {
  margin-top: var(--space-3);
}

.ask-impacted {
  margin-top: var(--space-3);
}

.ask-impacted-list {
  margin: var(--space-2) 0 0;
  font-size: var(--font-sm);
  color: var(--text);
  word-break: break-all;
}

.filter-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: var(--font-xs);
  color: var(--text-2);
}

.inline-alert {
  margin-top: var(--space-3);
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-3);
  margin-top: var(--space-4);
}

.summary-card {
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  border: var(--border-width) solid var(--border);
}

.summary-label {
  margin: 0;
  font-size: var(--font-xs);
  color: var(--text-2);
}

.summary-value {
  margin: var(--space-2) 0 0;
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--text);
}

.delta-positive {
  color: var(--ok);
}

.delta-negative {
  color: var(--danger);
}

.delta-neutral {
  color: var(--text-2);
}

.direction-hint {
  margin: var(--space-3) 0;
  font-size: var(--font-xs);
  color: var(--text-2);
  word-break: break-all;
}

.dialog-error {
  margin-bottom: var(--space-3);
}

.apply-result {
  margin-top: var(--space-3);
}
</style>
