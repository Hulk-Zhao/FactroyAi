<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { ApiError, createIdempotencyKey } from '@/api/http'
import { cloneStrategy, getStrategy, listStrategies } from '@/api/orders'
import { exportStrategy, importStrategy } from '@/api/strategies'
import { DataTable, StatusTag } from '@/components/common'
import { ERROR_CODES } from '@/constants/error-codes'
import { isMockEnabled } from '@/mocks'
import type { Strategy, StrategyDetail } from '@/types/orders'
import type {
  StrategyExportPackage,
  StrategyImportIssue,
  StrategyImportResult
} from '@/types/strategies'

import StrategyEditorDrawer from './StrategyEditorDrawer.vue'
import { SOFT_WEIGHT_TOTAL, sumEnabledSoftWeights } from './rule-catalog'

const strategies = ref<Strategy[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

const editorVisible = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const editingStrategy = ref<StrategyDetail | null>(null)

const exportPackage = ref<StrategyExportPackage | null>(null)
const exportDialogVisible = ref(false)
const exportingId = ref('')

const importInput = ref<HTMLInputElement | null>(null)
const importing = ref(false)
const confirming = ref(false)
const importDialogVisible = ref(false)
const importPayload = ref<StrategyExportPackage | null>(null)
const importResult = ref<StrategyImportResult | null>(null)
const importErrorText = ref('')

const CHECKSUM_PATTERN = /^[a-f0-9]{64}$/
const MOCK_UNAVAILABLE_MESSAGE = '该功能需连接真实后端，Mock 模式不提供策略导入导出'

const importIssues = computed<StrategyImportIssue[]>(() => importResult.value?.errors ?? [])
const canConfirmImport = computed(
  () => !importErrorText.value && !!importResult.value?.valid && !importResult.value?.existing
)

/** Mock 模式不提供导入导出（避免 mock 层伪造 checksum/结果），仅给出明确提示。 */
function notifyMockUnavailable(): boolean {
  if (!isMockEnabled()) {
    return false
  }
  ElMessage.warning(MOCK_UNAVAILABLE_MESSAGE)
  return true
}

async function loadStrategies() {
  loading.value = true
  error.value = ''
  try {
    const result = await listStrategies({ page: page.value, size: size.value })
    strategies.value = result.items
    total.value = result.total
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '策略列表加载失败'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editorMode.value = 'create'
  editingStrategy.value = null
  editorVisible.value = true
}

async function openEdit(row: Strategy) {
  loading.value = true
  try {
    editingStrategy.value = await getStrategy(row.id)
    editorMode.value = 'edit'
    editorVisible.value = true
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '策略详情加载失败'
  } finally {
    loading.value = false
  }
}

async function onClone(row: Strategy) {
  try {
    await cloneStrategy(row.id)
    ElMessage.success(`已克隆策略「${row.name}」`)
    await loadStrategies()
  } catch {
    // http 拦截器已统一提示
  }
}

/** 导出：checksum 一律取自后端响应，前端只负责展示与下载，不参与计算。 */
async function onExport(row: Strategy) {
  if (notifyMockUnavailable()) {
    return
  }
  exportingId.value = row.id
  try {
    const pkg = await exportStrategy(row.id)
    exportPackage.value = pkg
    downloadPackage(pkg)
    exportDialogVisible.value = true
  } catch {
    // http 拦截器已统一提示
  } finally {
    exportingId.value = ''
  }
}

function sanitizeFileName(name: string): string {
  return name.replace(/[\\/:*?"<>|\s]+/g, '_').slice(0, 64) || 'strategy'
}

function downloadPackage(pkg: StrategyExportPackage) {
  const blob = new Blob([JSON.stringify(pkg, null, 2)], {
    type: 'application/json;charset=utf-8'
  })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${sanitizeFileName(pkg.strategy.name)}.json`
  anchor.click()
  URL.revokeObjectURL(url)
}

async function copyChecksum() {
  const checksum = exportPackage.value?.checksum
  if (!checksum) {
    return
  }
  try {
    if (!navigator.clipboard?.writeText) {
      throw new Error('clipboard unavailable')
    }
    await navigator.clipboard.writeText(checksum)
    ElMessage.success('checksum 已复制')
  } catch {
    ElMessage.warning('当前环境不支持自动复制，请手动选中 checksum')
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 只做形状与 checksum 格式预检，不计算 checksum；内容是否被改动交给后端校验。 */
function parseImportPackage(text: string): StrategyExportPackage {
  let data: unknown
  try {
    data = JSON.parse(text)
  } catch {
    throw new Error('导入文件不是合法 JSON，无法解析')
  }
  if (!isRecord(data)) {
    throw new Error('导入文件必须是 JSON 对象')
  }
  if (!isRecord(data.strategy) || !Array.isArray(data.rules) || typeof data.checksum !== 'string') {
    throw new Error(
      '文件缺少 strategy/rules/checksum，不符合 C19 导出包形状；请从策略列表重新导出（旧版扁平 JSON 不再支持）'
    )
  }
  if (!CHECKSUM_PATTERN.test(data.checksum)) {
    throw new Error('checksum 缺失或格式非法（应为 64 位小写十六进制），文件可能被改动或不完整')
  }
  return data as unknown as StrategyExportPackage
}

async function onImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  importing.value = true
  importPayload.value = null
  importResult.value = null
  importErrorText.value = ''
  try {
    const payload = parseImportPackage(await file.text())
    importPayload.value = payload
    importDialogVisible.value = true
    const result = await importStrategy({ payload, dryRun: true })
    importResult.value = result
  } catch (exception) {
    importErrorText.value = describeImportError(exception)
    importDialogVisible.value = true
  } finally {
    importing.value = false
  }
}

/** 422/42200 不展示后端原文，统一给出可读的完整性提示。 */
function describeImportError(exception: unknown): string {
  if (exception instanceof ApiError) {
    if (exception.code === ERROR_CODES.UNPROCESSABLE || exception.httpStatus === 422) {
      return '文件被改动或不完整：checksum 缺失、格式非法，或内容与导出时不一致，已拒绝导入。请从策略列表重新导出后再导入。'
    }
    if (exception.code === ERROR_CODES.STATE_CONFLICT) {
      return (
        exception.message || '同名同版本的策略已存在且内容不同，导入被拒绝（后端不会自动升版本）。'
      )
    }
    return exception.message
  }
  return exception instanceof Error ? exception.message : '导入失败，请稍后重试'
}

async function confirmImport() {
  if (!importPayload.value || !canConfirmImport.value) {
    return
  }
  confirming.value = true
  try {
    const result = await importStrategy(
      { payload: importPayload.value, dryRun: false },
      createIdempotencyKey('strategy-import')
    )
    if (!result.valid) {
      importResult.value = result
      ElMessage.warning('校验未通过，本次未写入任何数据')
      return
    }
    importDialogVisible.value = false
    ElMessage.success(result.existing ? '策略已存在且内容一致，本次未新建记录' : '策略导入成功')
    await loadStrategies()
  } catch (exception) {
    importErrorText.value = describeImportError(exception)
  } finally {
    confirming.value = false
  }
}

function resetImportState() {
  importPayload.value = null
  importResult.value = null
  importErrorText.value = ''
}

function onImportClick() {
  if (notifyMockUnavailable()) {
    return
  }
  importInput.value?.click()
}

function softWeightOf(row: Strategy): number | null {
  if (!row.rules || row.rules.length === 0) {
    return null
  }
  return sumEnabledSoftWeights(row.rules)
}

function onSaved() {
  void loadStrategies()
}

onMounted(() => {
  void loadStrategies()
})
</script>

<template>
  <div>
    <h2 class="page-title">排产策略</h2>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :data="strategies"
      :loading="loading"
      :error="error"
      :total="total"
      row-key="id"
      empty-text="暂无排产策略"
      @change="loadStrategies"
      @retry="loadStrategies"
    >
      <template #toolbar>
        <input
          ref="importInput"
          class="file-input"
          type="file"
          accept="application/json,.json"
          @change="onImportFile"
        />
        <el-button type="primary" @click="openCreate">新建策略</el-button>
        <el-button :loading="importing" @click="onImportClick">导入 JSON</el-button>
        <el-button @click="loadStrategies">刷新</el-button>
      </template>

      <el-table-column prop="name" label="策略名称" min-width="180" />
      <el-table-column label="排产方向" width="120">
        <template #default="{ row }">
          <StatusTag enum-key="strategyDirection" :value="row.direction" />
        </template>
      </el-table-column>
      <el-table-column prop="strategyVersion" label="业务版本" width="100" />
      <el-table-column label="生效期" min-width="200">
        <template #default="{ row }">
          {{ row.effectiveFrom }} ~ {{ row.effectiveTo || '长期' }}
        </template>
      </el-table-column>
      <el-table-column label="规则数" width="90">
        <template #default="{ row }">{{ row.rules?.length ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="软约束权重和" width="140">
        <template #default="{ row }">
          <el-tag
            v-if="softWeightOf(row) !== null"
            :type="
              Math.abs((softWeightOf(row) ?? 0) - SOFT_WEIGHT_TOTAL) < 1e-6 ? 'success' : 'danger'
            "
            size="small"
          >
            {{ softWeightOf(row) }} / {{ SOFT_WEIGHT_TOTAL }}
          </el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="onClone(row)">克隆</el-button>
          <el-button link type="primary" :loading="exportingId === row.id" @click="onExport(row)">
            导出
          </el-button>
        </template>
      </el-table-column>
    </DataTable>

    <StrategyEditorDrawer
      v-model:visible="editorVisible"
      :mode="editorMode"
      :strategy="editingStrategy"
      @saved="onSaved"
    />

    <el-dialog v-model="exportDialogVisible" title="策略已导出" width="600px">
      <el-descriptions v-if="exportPackage" :column="1" border>
        <el-descriptions-item label="策略名称">
          {{ exportPackage.strategy.name }}
        </el-descriptions-item>
        <el-descriptions-item label="业务版本">
          v{{ exportPackage.strategy.strategyVersion }}
        </el-descriptions-item>
        <el-descriptions-item label="规则数">
          {{ exportPackage.rules.length }}
        </el-descriptions-item>
        <el-descriptions-item label="checksum">
          <div class="checksum-row">
            <el-input :model-value="exportPackage.checksum" readonly />
            <el-button @click="copyChecksum">复制</el-button>
          </div>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert
        class="dialog-alert"
        type="info"
        :closable="false"
        show-icon
        title="checksum 由后端按规范化内容计算，前端不参与计算；请勿手工修改导出文件，否则导入时会被判定为「文件被改动或不完整」。"
      />
      <template #footer>
        <el-button type="primary" @click="exportDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="importDialogVisible"
      title="导入策略 · 试运行预检"
      width="760px"
      v-loading="importing"
      @closed="resetImportState"
    >
      <el-descriptions v-if="importPayload" :column="2" border>
        <el-descriptions-item label="策略名称">
          {{ importPayload.strategy.name }}
        </el-descriptions-item>
        <el-descriptions-item label="排产方向">
          {{ importPayload.strategy.direction }}
        </el-descriptions-item>
        <el-descriptions-item label="业务版本">
          v{{ importPayload.strategy.strategyVersion }}
        </el-descriptions-item>
        <el-descriptions-item label="规则数">
          {{ importPayload.rules.length }}
        </el-descriptions-item>
        <el-descriptions-item label="checksum" :span="2">
          <span class="checksum-text">{{ importPayload.checksum }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="importErrorText"
        class="dialog-alert"
        type="error"
        :title="importErrorText"
        :closable="false"
        show-icon
      />
      <template v-else>
        <el-alert
          v-if="importResult?.existing"
          class="dialog-alert"
          type="warning"
          :closable="false"
          show-icon
          title="同名同版本策略已存在且内容一致：真导不会新建记录（幂等命中）。"
        />
        <el-alert
          v-else-if="importResult?.valid"
          class="dialog-alert"
          type="success"
          :closable="false"
          show-icon
          title="预检通过，确认后将按该载荷落库。"
        />

        <template v-if="importIssues.length">
          <div class="issue-summary">
            校验未通过，共 {{ importIssues.length }} 条问题；按「行 / 定位 / 错误码 /
            说明」逐条展示， 规则项定位形如 rules[0].weight。
          </div>
          <el-table :data="importIssues" border size="small">
            <el-table-column label="行" width="60">
              <template #default="{ $index }">{{ $index + 1 }}</template>
            </el-table-column>
            <el-table-column prop="field" label="定位（field）" min-width="180" />
            <el-table-column prop="code" label="错误码" width="100" />
            <el-table-column prop="message" label="说明" min-width="220" />
          </el-table>
        </template>
      </template>
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="confirming"
          :disabled="!canConfirmImport"
          @click="confirmImport"
        >
          确认导入
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.file-input {
  display: none;
}

.checksum-row {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}

.checksum-text {
  font-family: monospace;
  word-break: break-all;
}

.dialog-alert {
  margin-top: var(--space-3);
}

.issue-summary {
  margin: var(--space-3) 0 var(--space-2);
  color: var(--text-2);
  font-size: var(--font-sm);
}
</style>
