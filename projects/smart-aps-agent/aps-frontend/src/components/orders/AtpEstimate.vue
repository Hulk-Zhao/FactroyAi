<script setup lang="ts">
import { computed, ref } from 'vue'

import { queryAtp } from '@/api/orders'
import { ApiError } from '@/api/http'
import { setHelpContext } from '@/constants/help/context'
import { useTraceStore } from '@/stores/trace'
import type { AtpMode, AtpResult, Order } from '@/types/orders'

const props = defineProps<{
  order: Order
}>()

const traceStore = useTraceStore()

/** 用户操作 ATP 控件时，把帮助面板切到「交期预估（ATP）」场景。 */
function syncHelpContext(): void {
  setHelpContext('atp-estimate')
}

const mode = ref<AtpMode>('FAST')
/** 契约允许 1–30 秒；留空表示取服务端默认值。 */
const timeoutSeconds = ref<number | undefined>(undefined)
const loading = ref(false)
const result = ref<AtpResult | null>(null)
const errorText = ref('')
const traceId = ref('')

/** ACCURATE 是限时求解，必须给出"大约 10 秒"的等待预期，避免被当成卡死。 */
const loadingText = computed(() =>
  mode.value === 'ACCURATE'
    ? 'ACCURATE 限时求解中（实测约 10 秒，最长 30 秒），请勿关闭…'
    : 'FAST 快速估算中…'
)

/** 超时/不可行时响应没有任何日期字段，这里也不允许退化成 earliestDate 别名。 */
const hasTrustedDate = computed(() => {
  if (!result.value) {
    return false
  }
  if (result.value.status === 'TIMEOUT' || result.value.status === 'INFEASIBLE') {
    return false
  }
  return Boolean(result.value.earliestDeliveryDate)
})

const untrusted = computed(
  () =>
    Boolean(result.value) &&
    (result.value?.status === 'TIMEOUT' ||
      result.value?.status === 'FAILED' ||
      result.value?.status === 'INFEASIBLE' ||
      result.value?.accuracy === 'UNVERIFIED')
)

const timeoutEvidence = computed(() => {
  if (!result.value) {
    return ''
  }
  const item = result.value.bottlenecks?.find((entry) => entry.type === 'SOLVE_TIMEOUT')
  return item?.detail || result.value.message || ''
})

const accuracyLabel = computed(() => {
  const accuracy = result.value?.accuracy
  if (accuracy === 'VERIFIED') {
    return '已验证（ACCURATE 完整可行解）'
  }
  if (accuracy === 'ESTIMATE') {
    return '估算（FAST 构造启发式）'
  }
  if (accuracy === 'UNVERIFIED') {
    return '未验证（限时求解未得到完整可行解）'
  }
  return '—'
})

const advisoryText = computed(() => {
  const suggestion = result.value?.capacityLockSuggestion
  if (!suggestion) {
    return ''
  }
  const scope =
    suggestion.resourceCodes && suggestion.resourceCodes.length
      ? `资源 ${suggestion.resourceCodes.join('、')}`
      : ''
  const window =
    suggestion.from && suggestion.to ? `时间窗 ${suggestion.from} → ${suggestion.to}` : ''
  return [scope, window, suggestion.reason].filter(Boolean).join('；')
})

async function run(): Promise<void> {
  if (loading.value) {
    return
  }
  loading.value = true
  errorText.value = ''
  result.value = null
  traceId.value = ''
  try {
    const response = await queryAtp({
      materialCode: props.order.materialCode,
      qty: props.order.qty,
      requestedDate: props.order.dueDate,
      orderNo: props.order.orderNo,
      mode: mode.value,
      ...(mode.value === 'ACCURATE' && typeof timeoutSeconds.value === 'number'
        ? { timeoutSeconds: timeoutSeconds.value }
        : {})
    })
    result.value = response
    traceId.value = traceStore.latestTraceId
  } catch (exception) {
    const apiError = exception instanceof ApiError ? exception : null
    errorText.value = exception instanceof Error ? exception.message : '交期预估请求失败'
    traceId.value = apiError?.traceId || traceStore.latestTraceId
  } finally {
    loading.value = false
  }
}

/** 超时后一键改用 FAST；仍然是真实请求，不替用户编造日期。 */
function switchToFast(): void {
  mode.value = 'FAST'
  void run()
}
</script>

<template>
  <div class="atp" @focusin="syncHelpContext" @click="syncHelpContext">
    <div class="atp__bar">
      <el-radio-group v-model="mode" :disabled="loading" size="small">
        <el-radio-button value="FAST">FAST 快速估算</el-radio-button>
        <el-radio-button value="ACCURATE">ACCURATE 限时求解</el-radio-button>
      </el-radio-group>
      <el-input-number
        v-if="mode === 'ACCURATE'"
        v-model="timeoutSeconds"
        :min="1"
        :max="30"
        size="small"
        controls-position="right"
        placeholder="默认"
        style="width: 104px"
        :disabled="loading"
      />
      <span v-if="mode === 'ACCURATE'" class="atp__hint">限时秒数（1–30，留空取服务端默认）</span>
      <el-button type="primary" size="small" :loading="loading" @click="run">
        预估交期
      </el-button>
    </div>

    <div v-if="loading" class="atp__loading" role="status" aria-live="polite">
      <span class="atp__spinner" aria-hidden="true" />
      {{ loadingText }}
    </div>

    <el-alert
      v-if="errorText"
      type="error"
      :closable="false"
      show-icon
      class="atp__alert"
      :title="errorText"
      :description="traceId ? `traceId: ${traceId}` : ''"
    />

    <template v-if="result">
      <el-alert
        v-if="result.status === 'TIMEOUT' || result.accuracy === 'UNVERIFIED'"
        type="warning"
        :closable="false"
        show-icon
        class="atp__alert"
        title="系统未给出可信交期（限时求解超时）"
        :description="timeoutEvidence"
      >
        <el-button size="small" type="primary" plain @click="switchToFast">
          改用 FAST 估算
        </el-button>
      </el-alert>
      <el-alert
        v-else-if="untrusted"
        type="warning"
        :closable="false"
        show-icon
        class="atp__alert"
        title="系统未给出可信交期"
        :description="result.message || ''"
      />

      <template v-if="hasTrustedDate">
        <el-descriptions :column="2" border size="small" class="atp__result">
          <el-descriptions-item label="最早可交付">
            {{ result.earliestDeliveryDate }}
          </el-descriptions-item>
          <el-descriptions-item label="交期天数">
            {{ result.leadTimeDays ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="瓶颈资源">
            {{ result.bottleneckResourceCode || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="满足请求交期">
            {{ result.feasible ? '是' : '否' }}
          </el-descriptions-item>
          <el-descriptions-item label="可信度" :span="2">{{ accuracyLabel }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="result.capacityLockSuggestion?.advisoryOnly"
          type="info"
          :closable="false"
          show-icon
          class="atp__alert"
          title="产能锁定仅为建议，未占用产能"
          :description="advisoryText"
        />

        <div v-if="result.bottlenecks?.length" class="atp__list">
          <p class="atp__list-title">瓶颈证据</p>
          <ul>
            <li v-for="(item, index) in result.bottlenecks" :key="index">
              {{ item.type }}<template v-if="item.resourceCode"> · {{ item.resourceCode }}</template>
              · 延迟 {{ item.delayMinutes }} 分钟：{{ item.detail }}
            </li>
          </ul>
        </div>
        <div v-if="result.suggestions?.length" class="atp__list">
          <p class="atp__list-title">建议</p>
          <ul>
            <li v-for="(item, index) in result.suggestions" :key="index">
              {{ item.action }}：{{ item.detail }}
            </li>
          </ul>
        </div>
      </template>

      <p v-if="traceId" class="atp__trace">traceId: {{ traceId }}</p>
    </template>
  </div>
</template>

<style scoped>
.atp__bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.atp__hint {
  color: var(--text-2);
  font-size: var(--font-2xs);
}

.atp__loading {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
  color: var(--text-2);
  font-size: var(--font-sm);
}

.atp__spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--border);
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: atp-spin 0.9s linear infinite;
}

@keyframes atp-spin {
  to {
    transform: rotate(360deg);
  }
}

.atp__alert {
  margin-top: var(--space-3);
}

.atp__result {
  margin-top: var(--space-3);
}

.atp__list {
  margin-top: var(--space-3);
  color: var(--text-2);
  font-size: var(--font-sm);
}

.atp__list-title {
  margin: 0 0 var(--space-1);
  font-weight: 600;
  color: var(--text);
}

.atp__list ul {
  margin: 0;
  padding-left: 18px;
}

.atp__trace {
  margin: var(--space-3) 0 0;
  color: var(--text-3);
  font-size: var(--font-2xs);
  font-family: var(--font-mono, monospace);
}
</style>
