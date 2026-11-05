<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { comparePlans, listPlanVersions } from '@/api/plans'
import { DEFAULT_SANDBOX_ID } from '@/constants/defaults'
import type { PlanCompareItem, PlanCompareResult, PlanListItem } from '@/types/plans'

const sandboxId = ref(DEFAULT_SANDBOX_ID)
const planRows = ref<PlanListItem[]>([])
const listLoading = ref(false)
const listError = ref('')
const selectedPlanIds = ref<string[]>([])
const comparing = ref(false)
const compareError = ref('')
const result = ref<PlanCompareResult | null>(null)

const canCompare = computed(() => {
  const unique = [...new Set(selectedPlanIds.value)]
  return unique.length >= 2 && unique.length <= 3
})

const recommended = computed(() => {
  if (!result.value?.recommendedPlanId) {
    return null
  }
  return result.value.items.find((item) => item.planId === result.value?.recommendedPlanId) ?? null
})

function formatScore(value: number | null): string {
  return value === null || value === undefined ? '无' : String(value)
}

/**
 * metrics 原样展示：不做键排序、不改名、不裁剪，直接 JSON.stringify 后放进 pre。
 * 是否新增键由后端决定，前端只负责显示。
 */
function rawMetrics(metrics: PlanCompareItem['metrics']): string {
  return JSON.stringify(metrics, null, 2)
}

function rowClass({ row }: { row: PlanCompareItem }): string {
  return row.planId === result.value?.recommendedPlanId ? 'recommended-row' : ''
}

async function loadPlans() {
  listLoading.value = true
  listError.value = ''
  try {
    const response = await listPlanVersions({ sandboxId: sandboxId.value.trim(), page: 1, size: 50 })
    planRows.value = response.items
    if (planRows.value.length < 2) {
      listError.value = '该场景下可选计划版本不足 2 个，无法对比（请先发布至少 2 个版本）'
    }
  } catch (exception) {
    planRows.value = []
    listError.value = exception instanceof Error ? exception.message : '计划版本加载失败'
  } finally {
    listLoading.value = false
  }
}

async function onCompare() {
  compareError.value = ''
  // 前端先去重，再判 2–3 个；顺序按用户勾选顺序（后端会按自己的规则重排）。
  const unique = [...new Set(selectedPlanIds.value)]
  if (unique.length < 2 || unique.length > 3) {
    compareError.value = `去重后需要 2–3 个 planId，当前为 ${unique.length} 个`
    return
  }
  comparing.value = true
  try {
    result.value = await comparePlans(unique)
    if (!result.value.recommendedPlanId) {
      ElMessage.warning('后端未给出推荐计划（可能分数并列且无可比指标）')
    }
  } catch (exception) {
    result.value = null
    compareError.value = exception instanceof Error ? exception.message : '计划对比失败'
  } finally {
    comparing.value = false
  }
}

onMounted(() => {
  void loadPlans()
})
</script>

<template>
  <div>
    <h2 class="page-title">计划对比择优</h2>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="hint-alert"
      title="选择 2–3 个计划版本；前端先去重再判个数"
      description="响应中的 items 已按后端规则排序，页面直接渲染；recommendedPlanId 高亮，tieBreak 展示后端给出的并列裁决。metrics 原样展示，不美化重排。"
    />

    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-header">
          <span>选择计划版本</span>
          <span class="muted">POST /plans/compare</span>
        </div>
      </template>
      <div class="toolbar-row">
        <el-input v-model="sandboxId" placeholder="排产场景 ID" style="max-width: 460px" @keyup.enter="loadPlans" />
        <el-button type="primary" :loading="listLoading" @click="loadPlans">加载版本</el-button>
      </div>
      <el-alert v-if="listError" type="warning" :closable="false" :title="listError" class="inline-alert" />

      <el-table v-loading="listLoading" :data="planRows" row-key="planId" border class="plan-table">
        <el-table-column width="56">
          <template #default="{ row }">
            <el-checkbox
              v-model="selectedPlanIds"
              :value="row.planId"
              :disabled="!selectedPlanIds.includes(row.planId) && selectedPlanIds.length >= 3"
            />
          </template>
        </el-table-column>
        <el-table-column prop="planNo" label="计划号" min-width="180" />
        <el-table-column label="版本" width="90">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="发布时间" min-width="170">
          <template #default="{ row }">{{ row.publishedAt || '无' }}</template>
        </el-table-column>
        <el-table-column prop="taskCount" label="快照任务数" width="110" />
      </el-table>

      <div class="compare-actions">
        <span class="selection-hint">已选择 {{ selectedPlanIds.length }} 个（去重后最多 3 个）</span>
        <el-button type="primary" :loading="comparing" :disabled="!canCompare" @click="onCompare">
          开始对比
        </el-button>
      </div>
    </el-card>

    <el-alert v-if="compareError" type="error" :closable="false" show-icon :title="compareError" class="section" />

    <el-card v-if="result" shadow="never" class="section">
      <template #header>
        <div class="card-header">
          <span>对比结果</span>
          <span class="tie-break">tieBreak：{{ result.tieBreak }}</span>
        </div>
      </template>

      <el-alert
        v-if="recommended"
        type="success"
        :closable="false"
        show-icon
        class="inline-alert"
        :title="`推荐计划：${recommended.planNo}（v${recommended.version}）`"
        description="按硬约束、软约束得分与快照任务数综合推荐，仅供决策参考。"
      />

      <el-table
        :data="result.items"
        row-key="planId"
        :row-class-name="rowClass"
        border
        stripe
        class="result-table"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="metrics-panel">
              <p class="metrics-title">指标明细（原始数据）</p>
              <pre class="metrics-json">{{ rawMetrics(row.metrics) }}</pre>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="计划号" min-width="200">
          <template #default="{ row }">
            {{ row.planNo }}
            <el-tag v-if="row.planId === result.recommendedPlanId" type="success" size="small" class="recommend-tag">
              推荐
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="90">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="硬约束得分" width="120">
          <template #default="{ row }">{{ formatScore(row.scoreHard) }}</template>
        </el-table-column>
        <el-table-column label="软约束得分" width="120">
          <template #default="{ row }">{{ formatScore(row.scoreSoft) }}</template>
        </el-table-column>
        <el-table-column prop="taskCount" label="快照任务数" width="110" />
      </el-table>
    </el-card>
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

.muted,
.selection-hint {
  color: var(--text-2);
  font-size: var(--font-xs);
}

.toolbar-row {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  margin-bottom: var(--space-3);
}

.plan-table {
  margin-top: var(--space-2);
}

.inline-alert {
  margin-top: var(--space-3);
}

.compare-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-4);
}

.tie-break {
  color: var(--text-2);
  font-size: var(--font-xs);
}

.recommend-tag {
  margin-left: var(--space-2);
}

.metrics-panel {
  padding: var(--space-3) var(--space-6);
}

.metrics-title {
  margin: 0 0 var(--space-2);
  font-size: var(--font-xs);
  color: var(--text-2);
}

.metrics-json {
  margin: 0;
  max-height: 300px;
  overflow: auto;
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  border: var(--border-width) solid var(--border);
  color: var(--text);
  font-size: var(--font-xs);
  line-height: 1.7;
}

:deep(.recommended-row) {
  background: var(--primary-bg);
}

:deep(.recommended-row td) {
  background: transparent;
}
</style>
