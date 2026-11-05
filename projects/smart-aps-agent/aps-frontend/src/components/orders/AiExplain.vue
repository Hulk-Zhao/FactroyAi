<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import { getOrderGantt } from '@/api/gantt'
import { explainTask } from '@/api/solve'
import { ApiError } from '@/api/http'
import { clearHelpContext, setHelpContext } from '@/constants/help/context'
import { useTraceStore } from '@/stores/trace'
import type { ExplainConstraintImpact, ExplainResult } from '@/types/solve'
import type { Order } from '@/types/orders'

const props = withDefaults(
  defineProps<{
    order: Order
    /** 详情抽屉是否打开：关闭后不写帮助上下文，避免抢走页面级场景。 */
    active?: boolean
  }>(),
  { active: true }
)

const traceStore = useTraceStore()

const loading = ref(false)
const result = ref<ExplainResult | null>(null)
const errorText = ref('')
const noTaskText = ref('')
const traceId = ref('')
let loadToken = 0

const llmDisabled = computed(() => result.value?.llm?.configured === false)

/** 模型可用时展示后端的 AI 解读原文；证据来源为 EVIDENCE 时不展示，避免把模板文案当成模型输出。 */
const narrativeBlock = computed(() => (result.value?.source === 'LLM' ? result.value?.narrative ?? '' : ''))

/** 抽屉打开时把帮助面板定位到「AI 解读」；模型未启用则定位到降级场景。 */
function syncHelpContext(): void {
  if (!props.active) {
    return
  }
  setHelpContext(llmDisabled.value ? 'ai-disabled' : 'ai-explain')
}

watch(
  () => [props.active, llmDisabled.value] as const,
  () => syncHelpContext(),
  { immediate: true }
)

onBeforeUnmount(() => {
  clearHelpContext('ai-explain')
  clearHelpContext('ai-disabled')
})

/** 只展示后端证据里的约束名/扣分/说明，前端不做任何语义改写。 */
const impacts = computed<ExplainConstraintImpact[]>(() => result.value?.constraintImpacts ?? [])

const noDataText = computed(() => {
  const current = result.value
  if (!current) {
    return ''
  }
  if (current.narrative) {
    return current.narrative
  }
  if (current.noDataReason === 'AMBIGUOUS_SOLVE_TASK') {
    return '该任务对应多个求解批次，无法唯一定位归因。'
  }
  return '该任务暂无可用的约束归因记录。'
})

function deductionText(item: ExplainConstraintImpact): string {
  if (item.deduction !== null && item.deduction !== undefined) {
    return String(item.deduction)
  }
  if (item.scoreImpact) {
    return item.scoreImpact
  }
  return '—'
}

/** 订单本身不带 taskId：先用订单甘特按 orderNo 取已排任务，再请求归因解读。 */
async function resolveTaskId(): Promise<number | null> {
  const due = Date.parse(props.order.dueDate)
  const base = Number.isFinite(due) ? due : Date.now()
  const from = new Date(base - 14 * 86_400_000).toISOString()
  const to = new Date(base + 14 * 86_400_000).toISOString()
  const data = await getOrderGantt({ from, to, orderNos: [props.order.orderNo] })
  const row = data.orders.find((item) => item.orderNo === props.order.orderNo)
  return row?.tasks?.[0]?.id ?? null
}

async function load(): Promise<void> {
  const token = (loadToken += 1)
  loading.value = true
  result.value = null
  errorText.value = ''
  noTaskText.value = ''
  traceId.value = ''
  try {
    const taskId = await resolveTaskId()
    if (token !== loadToken) {
      return
    }
    if (taskId === null) {
      noTaskText.value = '该订单暂无已排任务，无法取约束归因。'
      return
    }
    const response = await explainTask(taskId)
    if (token !== loadToken) {
      return
    }
    result.value = response
    traceId.value = traceStore.latestTraceId
  } catch (exception) {
    if (token !== loadToken) {
      return
    }
    const apiError = exception instanceof ApiError ? exception : null
    errorText.value = exception instanceof Error ? exception.message : 'AI 解读请求失败'
    traceId.value = apiError?.traceId || traceStore.latestTraceId
  } finally {
    if (token === loadToken) {
      loading.value = false
    }
  }
}

watch(
  () => props.order.orderNo,
  () => {
    void load()
  },
  { immediate: true }
)
</script>

<template>
  <section class="ai-explain" @focusin="syncHelpContext" @click="syncHelpContext">
    <div class="ai-explain__head">
      <h3>AI 解读</h3>
      <el-button size="small" text :loading="loading" @click="load">重新解读</el-button>
    </div>

    <div v-if="loading" class="ai-explain__loading" role="status" aria-live="polite">
      正在读取求解证据…
    </div>

    <el-alert
      v-if="errorText"
      type="error"
      :closable="false"
      show-icon
      class="ai-explain__alert"
      :title="errorText"
      :description="traceId ? `traceId: ${traceId}` : ''"
    />

    <el-alert
      v-if="noTaskText"
      type="info"
      :closable="false"
      show-icon
      class="ai-explain__alert"
      title="暂无归因记录"
      :description="noTaskText"
    />

    <template v-if="result && result.available === false">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="ai-explain__alert"
        title="暂无归因记录"
        :description="noDataText"
      />
      <p v-if="traceId" class="ai-explain__meta">
        <span v-if="result.source">证据来源：{{ result.source }}</span>
        <span>traceId: {{ traceId }}</span>
      </p>
    </template>

    <template v-else-if="result">
      <el-alert
        v-if="llmDisabled"
        type="info"
        :closable="false"
        show-icon
        class="ai-explain__alert"
        title="AI 解读未启用，以下为求解证据原文"
        :description="result.llm?.fallbackReason || ''"
      />

      <div v-if="narrativeBlock" class="ai-explain__block">
        <p class="ai-explain__block-title">AI 解读</p>
        <p class="ai-explain__narrative">{{ narrativeBlock }}</p>
      </div>

      <p class="ai-explain__summary">{{ result.summary }}</p>

      <div v-if="impacts.length" class="ai-explain__block">
        <p class="ai-explain__block-title">约束归因</p>
        <el-table :data="impacts" size="small" border>
          <el-table-column label="约束名" min-width="170">
            <template #default="{ row }">
              {{ row.constraintName || row.constraintId || '—' }}
            </template>
          </el-table-column>
          <el-table-column label="扣分" width="90">
            <template #default="{ row }">{{ deductionText(row) }}</template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="220" />
        </el-table>
      </div>

      <div v-if="result.causes?.length" class="ai-explain__block">
        <p class="ai-explain__block-title">原因</p>
        <ul>
          <li v-for="(cause, index) in result.causes" :key="index">
            <strong>{{ cause.type }}</strong>：{{ cause.description }}
          </li>
        </ul>
      </div>

      <div v-if="result.suggestions?.length" class="ai-explain__block">
        <p class="ai-explain__block-title">建议</p>
        <ul>
          <li v-for="(item, index) in result.suggestions" :key="index">{{ item }}</li>
        </ul>
      </div>

      <p class="ai-explain__meta">
        <span v-if="result.source">证据来源：{{ result.source }}</span>
        <span v-if="result.llm">LLM：{{ result.llm.configured ? '已启用' : '未配置' }}</span>
        <span v-if="traceId">traceId: {{ traceId }}</span>
      </p>
    </template>
  </section>
</template>

<style scoped>
.ai-explain {
  border-top: 1px solid var(--border);
  padding-top: var(--space-4);
  margin-top: var(--space-4);
}

.ai-explain__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}

.ai-explain__head h3 {
  margin: 0;
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--text);
}

.ai-explain__narrative {
  margin: 0;
  line-height: 1.7;
  color: var(--text);
}

.ai-explain__loading {
  color: var(--text-2);
  font-size: var(--font-sm);
}

.ai-explain__alert {
  margin-bottom: var(--space-3);
}

.ai-explain__summary {
  margin: 0 0 var(--space-3);
  color: var(--text);
  line-height: 1.6;
}

.ai-explain__block {
  margin-bottom: var(--space-3);
  color: var(--text-2);
  font-size: var(--font-sm);
}

.ai-explain__block-title {
  margin: 0 0 var(--space-2);
  font-weight: 600;
  color: var(--text);
}

.ai-explain__block ul {
  margin: 0;
  padding-left: 18px;
}

.ai-explain__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin: 0;
  color: var(--text-3);
  font-size: var(--font-2xs);
  font-family: var(--font-mono, monospace);
}
</style>
