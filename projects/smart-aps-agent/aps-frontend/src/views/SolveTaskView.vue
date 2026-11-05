<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { cancelSolveTask, createSolveProgressClient, getSolveTask, submitSolveTask } from '@/api'
import type { SolveProgressChannelState } from '@/api/solve'
import { createSolveTracker, type SolveTracker } from '@/api/solveTracker'
import { StatusTag } from '@/components/common'
import type { SolveProgress, SolveTaskDetail, SolveTaskStatus, SolverEngine } from '@/types/solve'

const TERMINAL_STATUSES: SolveTaskStatus[] = ['SOLVED', 'FAILED', 'CANCELLED', 'TIMEOUT']

/** 产品默认预算保持 60/10；演示走“演示预置”一键 90/90。 */
const DEFAULT_TIMEOUT_SECONDS = 60
const DEFAULT_UNIMPROVED_SECONDS = 10
const TIMEOUT_MIN_SECONDS = 1
const TIMEOUT_MAX_SECONDS = 3600
const UNIMPROVED_MIN_SECONDS = 0
const UNIMPROVED_MAX_SECONDS = 3600
const DEMO_TIMEOUT_SECONDS = 90
const DEMO_UNIMPROVED_SECONDS = 90

const form = reactive<{
  taskNo: string
  engine: SolverEngine
  orderNos: string
  timeoutSeconds: number | undefined
  unimprovedSecondsSpentLimit: number | undefined
}>({
  taskNo: 'SOLVE-20261104-0001',
  engine: 'timefold',
  orderNos: '',
  timeoutSeconds: DEFAULT_TIMEOUT_SECONDS,
  unimprovedSecondsSpentLimit: DEFAULT_UNIMPROVED_SECONDS
})

const submitting = ref(false)
const cancelling = ref(false)
const error = ref('')
const detail = ref<SolveTaskDetail | null>(null)
const progress = ref<SolveProgress | null>(null)
const channelState = ref<SolveProgressChannelState | 'IDLE'>('IDLE')
const channelWarning = ref('')

let tracker: SolveTracker | null = null

const currentStatus = computed<SolveTaskStatus>(() => detail.value?.status ?? 'PENDING')
const currentProgress = computed(() => {
  const status = currentStatus.value
  if (status === 'SOLVED') {
    return 1
  }
  if (TERMINAL_STATUSES.includes(status)) {
    return 0
  }
  return progress.value?.progress ?? detail.value?.progress ?? 0
})
const percent = computed(() => Math.round(Math.min(1, Math.max(0, currentProgress.value)) * 100))
const barStatus = computed<'success' | 'exception' | undefined>(() => {
  if (currentStatus.value === 'SOLVED') {
    return 'success'
  }
  if (TERMINAL_STATUSES.includes(currentStatus.value)) {
    return 'exception'
  }
  return undefined
})
const phase = computed(() => progress.value?.phase ?? detail.value?.phase ?? '—')
const bestScore = computed(() => progress.value?.bestScore ?? detail.value?.bestScore ?? '—')
const elapsedMs = computed(() => progress.value?.elapsedMs ?? detail.value?.elapsedMs ?? null)
const isTerminal = computed(() => TERMINAL_STATUSES.includes(currentStatus.value))

const degraded = computed(() => detail.value?.metrics?.engineFallback === true)
const degradedReason = computed(() => {
  const reason = detail.value?.metrics?.engineFallbackReason
  return typeof reason === 'string' ? reason : ''
})

const channelTag = computed<{ type: 'success' | 'info' | 'warning'; label: string }>(() => {
  switch (channelState.value) {
    case 'CONNECTED':
      return { type: 'success', label: 'STOMP 已连接' }
    case 'CONNECTING':
      return { type: 'info', label: 'STOMP 连接中 · 2s 轮询兜底' }
    case 'DISCONNECTED':
      return { type: 'warning', label: 'STOMP 已断开 · 2s 轮询兜底' }
    case 'DISABLED':
      return { type: 'info', label: 'Mock 模式 · 2s 轮询兜底' }
    default:
      return { type: 'info', label: '未开始' }
  }
})

function isTerminalStatus(status: SolveTaskStatus): boolean {
  return TERMINAL_STATUSES.includes(status)
}

function formatElapsed(value: number | null): string {
  if (value === null || value <= 0) {
    return '—'
  }
  if (value < 1000) {
    return `${value} ms`
  }
  return `${(value / 1000).toFixed(1)} s`
}

/** 立即刷新：走同一个快照通道，终态即停止跟踪。 */
function refreshDetail() {
  void tracker?.refreshNow()
}

/**
 * STOMP 仅推送；`GET /solve-tasks/{id}` 是事实源。
 * 连接建立后无条件补一次快照（即使已收到首帧），快照失败时保留轮询兜底。
 */
function startTracking(task: SolveTaskDetail) {
  stopTracking()
  progress.value = null
  channelWarning.value = ''
  channelState.value = 'CONNECTING'
  tracker = createSolveTracker(
    {
      onDetail: (next) => {
        detail.value = next
      },
      onProgress: (message) => {
        progress.value = message
      },
      onTerminal: () => {
        // 以事实源为准，丢弃早于终态的推送帧
        progress.value = null
      },
      onChannelState: (state) => {
        channelState.value = state
      },
      onWarning: (message) => {
        channelWarning.value = message
      }
    },
    {
      getDetail: (taskId) => getSolveTask(taskId),
      subscribe: (taskId, handlers) => createSolveProgressClient(taskId, handlers)
    }
  )
  tracker.start(task)
}

function stopTracking() {
  tracker?.stop()
  tracker = null
}

function isValidBudget(value: number | undefined, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max
}

/** 演示预置：只改本次表单，不改变产品默认 60/10。 */
function applyDemoPreset(): void {
  form.timeoutSeconds = DEMO_TIMEOUT_SECONDS
  form.unimprovedSecondsSpentLimit = DEMO_UNIMPROVED_SECONDS
  error.value = ''
  ElMessage.success('已填入演示预置：timeoutSeconds=90、unimprovedSecondsSpentLimit=90')
}

async function onSubmit() {
  error.value = ''
  const timeoutSeconds = form.timeoutSeconds
  const unimprovedSecondsSpentLimit = form.unimprovedSecondsSpentLimit
  if (!isValidBudget(timeoutSeconds, TIMEOUT_MIN_SECONDS, TIMEOUT_MAX_SECONDS)) {
    error.value = `超时秒数需为 ${TIMEOUT_MIN_SECONDS}-${TIMEOUT_MAX_SECONDS} 的整数`
    return
  }
  if (!isValidBudget(
    unimprovedSecondsSpentLimit,
    UNIMPROVED_MIN_SECONDS,
    UNIMPROVED_MAX_SECONDS
  )) {
    error.value = `无改进上限需为 ${UNIMPROVED_MIN_SECONDS}-${UNIMPROVED_MAX_SECONDS} 的整数`
    return
  }
  submitting.value = true
  stopTracking()
  detail.value = null
  progress.value = null
  channelState.value = 'IDLE'
  try {
    const orderNos = form.orderNos
      .split(',')
      .map((orderNo) => orderNo.trim())
      .filter((orderNo) => orderNo.length > 0)
    const created = await submitSolveTask({
      taskNo: form.taskNo,
      engine: form.engine,
      orderNos,
      parameters: { timeoutSeconds, unimprovedSecondsSpentLimit }
    })
    if (typeof created?.id !== 'number') {
      error.value = '接口未按 C20 返回 SolveTaskDetail（缺少 id），无法订阅进度'
      return
    }
    startTracking(created)
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '求解请求失败'
  } finally {
    submitting.value = false
  }
}

async function onCancel() {
  if (!detail.value) {
    return
  }
  cancelling.value = true
  try {
    detail.value = await cancelSolveTask(detail.value.id)
    ElMessage.success('求解任务已取消')
    if (isTerminalStatus(detail.value.status)) {
      stopTracking()
    }
  } catch {
    // http 拦截器已统一提示
  } finally {
    cancelling.value = false
  }
}

onBeforeUnmount(() => {
  stopTracking()
})
</script>

<template>
  <div>
    <h2 class="page-title">排产求解</h2>
    <el-card class="page-card" shadow="never">
      <el-form label-width="100px" @submit.prevent>
        <el-form-item label="任务号">
          <el-input v-model="form.taskNo" placeholder="请输入求解任务号" />
        </el-form-item>
        <el-form-item label="求解引擎">
          <el-select v-model="form.engine">
            <el-option label="Timefold（默认）" value="timefold" />
            <el-option label="OR-Tools（预留）" value="ortools" />
            <el-option label="规则引擎（兼容）" value="rule_based" />
          </el-select>
        </el-form-item>
        <el-form-item label="订单号">
          <el-input v-model="form.orderNos" placeholder="多个订单号以英文逗号分隔" />
        </el-form-item>
        <el-form-item label="超时秒数">
          <el-input-number
            v-model="form.timeoutSeconds"
            :min="TIMEOUT_MIN_SECONDS"
            :max="TIMEOUT_MAX_SECONDS"
            :step="10"
            controls-position="right"
            @change="error = ''"
          />
          <span class="budget-key">parameters.timeoutSeconds（1-3600，默认 60）</span>
        </el-form-item>
        <el-form-item label="无改进上限">
          <el-input-number
            v-model="form.unimprovedSecondsSpentLimit"
            :min="UNIMPROVED_MIN_SECONDS"
            :max="UNIMPROVED_MAX_SECONDS"
            :step="10"
            controls-position="right"
            @change="error = ''"
          />
          <span class="budget-key">
            parameters.unimprovedSecondsSpentLimit（0-3600，默认 10）
          </span>
        </el-form-item>
        <el-form-item label="等待预期">
          <el-alert
            class="budget-hint"
            type="info"
            :closable="false"
            show-icon
            title="单次最多约 90 秒、失败重试再加 30 秒；默认 60/10 更短，演示请点“演示预置”。"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="onSubmit">提交求解</el-button>
          <el-button :disabled="submitting" @click="applyDemoPreset">演示预置（90/90）</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-alert v-if="error" class="page-card" type="error" :closable="false" :title="error" />

    <el-card v-if="detail" shadow="never">
      <template #header>
        <div class="panel-header">
          <span>求解进度 #{{ detail.id }}</span>
          <div class="panel-tags">
            <StatusTag enum-key="solveTaskStatus" :value="currentStatus" />
            <el-tag :type="channelTag.type" size="small">{{ channelTag.label }}</el-tag>
            <el-tooltip v-if="degraded" :content="degradedReason || '引擎降级求解'">
              <el-tag type="warning" size="small">降级</el-tag>
            </el-tooltip>
          </div>
        </div>
      </template>

      <el-progress :percentage="percent" :status="barStatus" :stroke-width="16" />
      <el-alert
        v-if="channelWarning"
        class="channel-warning"
        type="warning"
        :closable="false"
        :title="channelWarning"
      />

      <el-descriptions class="panel-detail" :column="3" border>
        <el-descriptions-item label="任务号">{{ detail.taskNo }}</el-descriptions-item>
        <el-descriptions-item label="引擎">{{ detail.engine }}</el-descriptions-item>
        <el-descriptions-item label="沙箱">{{ detail.sandboxId }}</el-descriptions-item>
        <el-descriptions-item label="阶段">{{ phase }}</el-descriptions-item>
        <el-descriptions-item label="最优分数">{{ bestScore }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ formatElapsed(elapsedMs) }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ detail.startedAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ detail.finishedAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="结果任务">
          {{ detail.resultTaskId ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="说明" :span="3">
          {{ detail.errorMessage || progress?.message || '—' }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="panel-actions">
        <el-button :disabled="isTerminal" :loading="cancelling" @click="onCancel">
          取消求解
        </el-button>
        <el-button @click="refreshDetail">立即刷新</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.panel-tags {
  display: flex;
  gap: 8px;
}

.channel-warning,
.panel-detail {
  margin-top: 12px;
}

.budget-key {
  margin-left: var(--space-2);
  color: var(--text-3);
  font-size: var(--font-xs);
}

.budget-hint {
  width: 100%;
}

.panel-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
</style>
