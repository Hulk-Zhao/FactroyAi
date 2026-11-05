<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { listMaterials } from '@/api/materials'
import { runMrpAnalysis } from '@/api/mrp'
import { listSandboxes } from '@/api/orders'
import { PageState } from '@/components/common'
import { DEFAULT_SANDBOX_ID } from '@/constants/defaults'
import type { Material } from '@/types/materials'
import type { MrpOrderResult } from '@/types/mrp'
import type { Sandbox } from '@/types/orders'

import {
  affectedOrdersOf,
  arrivalText,
  displayOrDash,
  formatDate,
  formatDateTime,
  formatQty,
  isSandboxId,
  orderKittingCount,
  orderKittingMeta,
  parseOrderNos,
  remainingAfterSubstitutes,
  resolveMrpStatusMeta,
  shortagesOf,
  substitutesOf
} from './mrp-utils'

interface ResultAlert {
  type: 'success' | 'warning' | 'info' | 'error'
  title: string
  description: string
}

const sandboxId = ref(DEFAULT_SANDBOX_ID)
const orderNosText = ref('')
const includeSuggestions = ref(true)
const blocking = ref(false)
const sandboxes = ref<Sandbox[]>([])
const materialMap = ref<Record<string, Material>>({})

const loading = ref(false)
const error = ref('')
const result = ref<Awaited<ReturnType<typeof runMrpAnalysis>> | null>(null)
const activeTab = ref<'orders' | 'materials'>('orders')
const lastIncludeSuggestions = ref(true)

/** 顶层 status 是整批汇总；订单行三态以 orders[].kittingStatus 为准。 */
const batchStatusMeta = computed(() => resolveMrpStatusMeta(result.value?.status))
const orders = computed(() => result.value?.orders ?? [])
const shortages = computed(() => result.value?.shortages ?? [])
const warnings = computed(() => result.value?.warnings ?? [])
const analyzedAtText = computed(() => formatDateTime(result.value?.analyzedAt))
const sandboxText = computed(() => {
  const id = result.value?.sandboxId
  if (!id) {
    return '无'
  }
  const matched = sandboxes.value.find((item) => item.id === id)
  return matched ? matched.name : '场景名称待接入'
})

function materialDisplay(code?: string | null): string {
  if (!code) {
    return '无'
  }
  const name = materialMap.value[code]?.name
  return name ? `${name}（${code}）` : code
}

async function loadLookups() {
  try {
    const materials = await listMaterials({ page: 1, size: 200, sort: 'code,asc' })
    const map: Record<string, Material> = {}
    materials.items.forEach((item) => {
      map[item.code] = item
    })
    materialMap.value = map
  } catch {
    // 主数据不可用不影响 MRP 分析，名称列退回后端已返回的 materialName
  }
  try {
    const page = await listSandboxes({ page: 1, size: 100 })
    sandboxes.value = page.items
  } catch {
    // 场景列表不可用时退回手输场景 ID
  }
}

onMounted(() => {
  void loadLookups()
})
const inventoryEnabled = computed(() => result.value?.inventoryEnabled ?? true)
const noOrders = computed(() => result.value !== null && (result.value.orderCount ?? 0) === 0)

const blockingAlert = computed<ResultAlert | null>(() => {
  const current = result.value
  if (!current) {
    return null
  }
  if (!current.blocking) {
    return {
      type: 'info',
      title: '仅分析模式（blocking=false）',
      description: '本次结果只用于预览缺料，不会阻断排产。'
    }
  }
  if (current.blocked) {
    return {
      type: 'error',
      title: '排产已被阻塞',
      description: `blocking=true 且存在 ${shortages.value.length} 种缺料物料，排产装配侧会拦截本批订单。`
    }
  }
  return {
    type: 'success',
    title: '齐套通过，放行排产',
    description: 'blocking=true 且未发现缺料，排产可继续。'
  }
})

function orderShortages(row: MrpOrderResult) {
  return shortagesOf(row)
}

async function analyze() {
  const sandbox = sandboxId.value.trim()
  if (!isSandboxId(sandbox)) {
    ElMessage.warning('场景 ID 需为 UUID 格式，可直接使用默认场景 ID')
    return
  }
  const orderNos = parseOrderNos(orderNosText.value)
  loading.value = true
  error.value = ''
  try {
    result.value = await runMrpAnalysis({
      sandboxId: sandbox,
      orderNos: orderNos.length > 0 ? orderNos : undefined,
      includeSuggestions: includeSuggestions.value,
      blocking: blocking.value
    })
    lastIncludeSuggestions.value = includeSuggestions.value
    activeTab.value = 'orders'
  } catch (exception) {
    result.value = null
    error.value = exception instanceof Error ? exception.message : 'MRP 分析失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div>
    <h2 class="page-title">MRP 缺料分析</h2>

    <el-card shadow="never" class="page-card">
      <el-form :inline="true" @submit.prevent="analyze">
        <el-form-item label="排产场景">
          <el-select
            v-if="sandboxes.length"
            v-model="sandboxId"
            filterable
            placeholder="选择排产场景"
            style="width: 320px"
          >
            <el-option
              v-for="item in sandboxes"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <el-input
            v-else
            v-model="sandboxId"
            clearable
            placeholder="场景 ID"
            style="width: 320px"
          />
        </el-form-item>
        <el-form-item label="订单号">
          <el-input
            v-model="orderNosText"
            clearable
            placeholder="留空分析全部；多个订单号用逗号或换行分隔"
            style="width: 360px"
            @keyup.enter="analyze"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="includeSuggestions">输出采购建议</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-switch v-model="blocking" active-text="缺料阻塞排产" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="analyze">开始分析</el-button>
        </el-form-item>
      </el-form>
      <div class="form-hint">
        可用量 = 现有库存 + 在途数量 − 安全库存；数量按 NUMERIC(18,4) 展示，不做整数舍入。
        切换“锁定缺料阻塞排产”或“输出采购建议”后需重新分析生效。
      </div>
    </el-card>

    <el-alert
      v-if="error"
      class="page-alert"
      type="error"
      :closable="false"
      show-icon
      :title="error"
      description="请确认后端 MRP 接口可用后重试。"
    />

    <el-card v-if="result" v-loading="loading" shadow="never" class="page-card">
      <div class="result-head">
        <span class="result-meta">整批汇总</span>
        <el-tag :type="batchStatusMeta.tagType" size="large" effect="dark">
          {{ batchStatusMeta.label }}
        </el-tag>
        <span class="result-meta result-hint">订单级三态见下表</span>
        <span class="result-meta">分析时间：{{ analyzedAtText }}</span>
        <span class="result-meta">订单数：{{ result.orderCount }}</span>
        <span class="result-meta">缺料物料：{{ shortages.length }}</span>
        <span class="result-meta">场景：{{ sandboxText }}</span>
      </div>

      <el-alert
        v-if="blockingAlert"
        class="page-alert"
        :type="blockingAlert.type"
        :closable="false"
        show-icon
        :title="blockingAlert.title"
        :description="blockingAlert.description"
      />

      <el-alert
        v-if="!inventoryEnabled"
        class="page-alert"
        type="warning"
        :closable="false"
        show-icon
        title="库存数据未参与计算"
        description="后端 aps.mrp.inventory-enabled=false，本次齐套结果未扣减库存与在途。"
      />

      <el-alert
        v-if="noOrders"
        class="page-alert"
        type="warning"
        :closable="false"
        show-icon
        title="未匹配到可分析订单"
        description="请检查订单号是否拼错，或订单状态已取消/已完成（不在分析范围）。"
      />

      <el-alert
        v-for="(warning, index) in warnings"
        :key="`${index}-${warning}`"
        class="page-alert"
        type="warning"
        :closable="false"
        show-icon
        :title="warning"
      />

      <el-tabs v-model="activeTab" class="result-tabs">
        <el-tab-pane :label="`按订单（${orders.length}）`" name="orders">
          <el-table
            :data="orders"
            row-key="orderNo"
            border
            stripe
            max-height="460"
            empty-text="本次分析没有可分析订单"
          >
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="expand-body">
                  <el-table
                    v-if="orderShortages(row).length > 0"
                    :data="orderShortages(row)"
                    size="small"
                    border
                    empty-text="无缺料"
                  >
                    <el-table-column prop="materialCode" label="物料编码" min-width="160" />
                    <el-table-column label="物料名称" min-width="160">
                      <template #default="{ row: shortage }">
                        {{ displayOrDash(shortage.materialName) }}
                      </template>
                    </el-table-column>
                    <el-table-column label="需求数量" width="150" align="right">
                      <template #default="{ row: shortage }">
                        {{ formatQty(shortage.requiredQty) }}
                      </template>
                    </el-table-column>
                    <el-table-column label="已分配" width="150" align="right">
                      <template #default="{ row: shortage }">
                        {{ formatQty(shortage.allocatedQty) }}
                      </template>
                    </el-table-column>
                    <el-table-column label="缺料数量" width="150" align="right">
                      <template #default="{ row: shortage }">
                        <span class="shortage-qty">{{ formatQty(shortage.shortageQty) }}</span>
                      </template>
                    </el-table-column>
                  </el-table>
                  <span v-else class="kitted-hint">该订单物料齐套，无缺料。</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="orderNo" label="订单号" min-width="170" />
            <el-table-column label="产品物料" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">{{ materialDisplay(row.productCode) }}</template>
            </el-table-column>
            <el-table-column label="订单数量" width="150" align="right">
              <template #default="{ row }">{{ formatQty(row.qty) }}</template>
            </el-table-column>
            <el-table-column label="交期" width="120">
              <template #default="{ row }">{{ formatDate(row.dueDate) }}</template>
            </el-table-column>
            <el-table-column label="齐套状态" width="110">
              <template #default="{ row }">
                <el-tag :type="orderKittingMeta(row).tagType" size="small">
                  {{ orderKittingMeta(row).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="缺料项（缺/总）" width="140" align="right">
              <template #default="{ row }">{{ orderKittingCount(row) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`按物料（${shortages.length}）`" name="materials">
          <el-table
            :data="shortages"
            row-key="materialCode"
            border
            stripe
            max-height="460"
            empty-text="本次分析没有缺料物料"
          >
            <el-table-column label="物料名称" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                {{ displayOrDash(row.materialName) }}
              </template>
            </el-table-column>
            <el-table-column prop="materialCode" label="物料编码" min-width="160">
              <template #default="{ row }">
                <span class="secondary-text">{{ row.materialCode }}</span>
              </template>
            </el-table-column>
            <el-table-column label="需求数量" width="150" align="right">
              <template #default="{ row }">{{ formatQty(row.requiredQty) }}</template>
            </el-table-column>
            <el-table-column label="可用数量" width="150" align="right">
              <template #default="{ row }">
                <span :class="{ negative: Number(row.availableQty) < 0 }">
                  {{ formatQty(row.availableQty) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="缺料数量" width="150" align="right">
              <template #default="{ row }">
                <span class="shortage-qty">{{ formatQty(row.shortageQty) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="替代料（可用量）" min-width="240">
              <template #default="{ row }">
                <div v-if="substitutesOf(row).length > 0" class="substitute-list">
                  <div
                    v-for="substitute in substitutesOf(row)"
                    :key="substitute.materialCode"
                    class="substitute-line"
                  >
                    <span class="substitute-code">{{ materialDisplay(substitute.materialCode) }}</span>
                    <span class="substitute-avail">
                      可用 {{ formatQty(substitute.availableQty) }}
                    </span>
                    <el-tag v-if="substitute.ratio" size="small" type="info">
                      {{ substitute.ratio }}
                    </el-tag>
                  </div>
                </div>
                <span v-else class="none-text">无</span>
              </template>
            </el-table-column>
            <el-table-column label="替代后缺口" width="190">
              <template #default="{ row }">{{ remainingAfterSubstitutes(row) }}</template>
            </el-table-column>
            <el-table-column label="预计到货" width="120">
              <template #default="{ row }">{{ arrivalText(row) }}</template>
            </el-table-column>
            <el-table-column label="最早需求时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.earliestRequiredAt) }}</template>
            </el-table-column>
            <el-table-column label="受影响订单" min-width="220">
              <template #default="{ row }">
                <div class="order-tags">
                  <el-tag
                    v-for="orderNo in affectedOrdersOf(row)"
                    :key="orderNo"
                    size="small"
                    type="info"
                  >
                    {{ orderNo }}
                  </el-tag>
                  <span v-if="affectedOrdersOf(row).length === 0">—</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column v-if="lastIncludeSuggestions" label="建议下单日期" width="170">
              <template #default="{ row }">{{
                formatDateTime(row.suggestedPurchaseDate)
              }}</template>
            </el-table-column>
            <el-table-column
              v-if="lastIncludeSuggestions"
              label="建议数量"
              width="150"
              align="right"
            >
              <template #default="{ row }">{{ formatQty(row.suggestedQty) }}</template>
            </el-table-column>
            <el-table-column v-if="lastIncludeSuggestions" label="供应商" width="140">
              <template #default="{ row }">{{ displayOrDash(row.supplierCode) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-card v-else shadow="never" class="page-card">
      <PageState
        state="empty"
        description="设置场景与订单号后点击「开始分析」，齐套分级与缺料明细会在这里展示。"
      />
    </el-card>
  </div>
</template>

<style scoped>
.form-hint {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.6;
}

.page-alert {
  margin: 12px 0;
}

.result-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 20px;
}

.result-meta {
  color: var(--text-2);
  font-size: 13px;
}

.result-hint {
  color: var(--text-3);
}

.substitute-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.substitute-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.substitute-code {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}

.substitute-avail {
  color: var(--text-3);
  font-size: 12px;
}

.none-text,
.secondary-text {
  color: var(--text-3);
}

.secondary-text {
  font-size: 12px;
}

.result-tabs {
  margin-top: 16px;
}

.expand-body {
  padding: 4px 16px;
}

.kitted-hint {
  color: var(--ok);
  font-size: 13px;
}

.shortage-qty {
  color: var(--el-color-danger);
  font-weight: 600;
}

.negative {
  color: var(--el-color-warning);
  font-weight: 600;
}

.order-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
</style>
