<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile, UploadInstance } from 'element-plus'

import { ApiError } from '@/api/http'
import {
  cancelImportJob,
  createImportToken,
  downloadEntityExport,
  downloadImportTemplate,
  executeImport,
  getImportJob,
  previewImport,
  submitAsyncImport
} from '@/api/importExport'
import {
  IMPORT_ACCEPTED_EXTENSION,
  IMPORT_ENTITY_OPTIONS,
  IMPORT_ERROR_HINTS,
  IMPORT_MAX_FILE_BYTES,
  IMPORT_MAX_ROWS,
  findImportEntity
} from '@/constants/import'
import type { ImportJobStatus, ImportJobView, ImportResult } from '@/types/import'

import ImportErrorTable from './components/ImportErrorTable.vue'

type SyncMode = 'sync' | 'async'
type BusyState = '' | 'preview' | 'execute' | 'async' | 'cancel' | 'download'

const JOB_STATUS_LABELS: Record<ImportJobStatus, string> = {
  PENDING: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '已成功',
  FAILED: '已失败',
  CANCELLED: '已取消'
}

const ERROR_MAPPING = [
  {
    code: 42200,
    meaning: '业务规则校验未通过',
    behavior:
      '文件超过 5MB、超过 5000 行或行级校验失败；展示后端 message 与 {row, field, code, message}'
  },
  {
    code: 40000,
    meaning: '请求参数不合法',
    behavior: '如 mode 非法、文件为空；保留后端原始提示'
  },
  {
    code: 40300,
    meaning: '无权访问该资源',
    behavior: '无导入/导出权限；沿用 http.ts 拦截器展示，不新增鉴权逻辑'
  },
  {
    code: 40901,
    meaning: '任务状态冲突',
    behavior: '仅 PENDING/RUNNING 可取消；终态取消返回 40901，页面叠加可读提示'
  }
]

const entitySlug = ref('customers')
const importMode = ref<SyncMode>('sync')
const file = ref<File | null>(null)
const precheckError = ref('')
const busy = ref<BusyState>('')

const uploadRef = ref<UploadInstance>()

const syncReport = ref<{ title: string; result: ImportResult } | null>(null)
const syncAction = ref<'preview' | 'execute' | null>(null)

const job = ref<ImportJobView | null>(null)
const jobEntity = ref('')
const pageError = ref('')

let pollTimer: number | undefined

const entityOption = computed(() => findImportEntity(entitySlug.value))
const entityColumns = computed(() => entityOption.value?.columns ?? [])

const jobActive = computed(
  () => Boolean(job.value) && (job.value?.status === 'PENDING' || job.value?.status === 'RUNNING')
)

const stepActive = computed(() => {
  if (importMode.value === 'async') {
    if (!job.value) {
      return file.value ? 1 : 0
    }
    return jobActive.value ? 2 : 3
  }
  if (syncAction.value === 'execute' && syncReport.value?.result.committed) {
    return 4
  }
  if (syncReport.value) {
    return 2
  }
  return file.value ? 1 : 0
})

const jobPercent = computed(() => {
  if (!job.value) {
    return 0
  }
  if (job.value.status === 'SUCCEEDED') {
    return 100
  }
  if (job.value.totalRows <= 0) {
    return job.value.status === 'RUNNING' ? 20 : 0
  }
  return Math.min(100, Math.round((job.value.processedRows / job.value.totalRows) * 100))
})

const jobProgressStatus = computed<'success' | 'warning' | 'exception' | undefined>(() => {
  if (!job.value) {
    return undefined
  }
  if (job.value.status === 'FAILED') {
    return 'exception'
  }
  if (job.value.status === 'CANCELLED') {
    return 'warning'
  }
  if (job.value.status === 'SUCCEEDED') {
    return 'success'
  }
  return undefined
})

function jobStatusLabel(status: ImportJobStatus): string {
  return JOB_STATUS_LABELS[status]
}

function jobTagType(status: ImportJobStatus): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'SUCCEEDED') {
    return 'success'
  }
  if (status === 'FAILED') {
    return 'danger'
  }
  if (status === 'RUNNING') {
    return 'warning'
  }
  return 'info'
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(2)} MB`
  }
  if (bytes >= 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${bytes} B`
}

function formatCell(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  if (typeof value === 'boolean') {
    return value ? 'true' : 'false'
  }
  if (typeof value === 'object') {
    return JSON.stringify(value)
  }
  return String(value)
}

function describeFailure(error: unknown): string {
  if (error instanceof ApiError) {
    const hint = IMPORT_ERROR_HINTS[error.code]
    const message = error.message?.trim() || '请求失败'
    return hint ? `${message}（${error.code} ${hint}）` : `${message}（错误码 ${error.code}）`
  }
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

function stopPolling(): void {
  if (pollTimer !== undefined) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

function resetResults(): void {
  stopPolling()
  syncReport.value = null
  syncAction.value = null
  job.value = null
  jobEntity.value = ''
  pageError.value = ''
}

function validateLocalFile(candidate: File): string {
  const extension = candidate.name.toLowerCase().slice(candidate.name.lastIndexOf('.'))
  if (extension !== IMPORT_ACCEPTED_EXTENSION) {
    return `仅支持 ${IMPORT_ACCEPTED_EXTENSION} 文件；前端预检不替代后端校验`
  }
  if (candidate.size > IMPORT_MAX_FILE_BYTES) {
    return `导入文件不能超过 5MB（当前 ${formatSize(candidate.size)}）；前端预检不替代后端 42200 判定`
  }
  return ''
}

function handleFileChange(uploadFile: UploadFile): void {
  const raw = uploadFile.raw
  if (!raw) {
    return
  }
  const message = validateLocalFile(raw)
  if (message) {
    file.value = null
    precheckError.value = message
    uploadRef.value?.clearFiles()
    ElMessage.warning(message)
    return
  }
  file.value = raw
  precheckError.value = ''
  resetResults()
}

function handleFileRemove(): void {
  file.value = null
  precheckError.value = ''
  resetResults()
}

function handleExceed(): void {
  ElMessage.warning('一次只能选择一个文件，请先移除当前文件')
}

function clearFile(): void {
  uploadRef.value?.clearFiles()
  handleFileRemove()
}

function handleEntityChange(): void {
  uploadRef.value?.clearFiles()
  file.value = null
  precheckError.value = ''
  resetResults()
}

function handleModeChange(): void {
  resetResults()
}

function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}

async function runDownloadTemplate(): Promise<void> {
  if (busy.value) {
    return
  }
  busy.value = 'download'
  pageError.value = ''
  try {
    const downloaded = await downloadImportTemplate(entitySlug.value)
    saveBlob(downloaded.blob, downloaded.filename)
    ElMessage.success(`模板已下载：${downloaded.filename}`)
  } catch (error) {
    pageError.value = describeFailure(error)
  } finally {
    busy.value = ''
  }
}

async function runExport(): Promise<void> {
  if (busy.value) {
    return
  }
  busy.value = 'download'
  pageError.value = ''
  try {
    const downloaded = await downloadEntityExport(entitySlug.value)
    saveBlob(downloaded.blob, downloaded.filename)
    ElMessage.success(`导出已下载：${downloaded.filename}`)
  } catch (error) {
    pageError.value = describeFailure(error)
  } finally {
    busy.value = ''
  }
}

async function runPreview(): Promise<void> {
  if (!file.value || busy.value) {
    return
  }
  busy.value = 'preview'
  pageError.value = ''
  stopPolling()
  try {
    const result = await previewImport(entitySlug.value, file.value)
    syncAction.value = 'preview'
    syncReport.value = { title: '预览结果（committed=false，只校验不落库）', result }
  } catch (error) {
    syncReport.value = null
    syncAction.value = null
    pageError.value = describeFailure(error)
  } finally {
    busy.value = ''
  }
}

async function runExecute(): Promise<void> {
  if (!file.value || busy.value) {
    return
  }
  busy.value = 'execute'
  pageError.value = ''
  stopPolling()
  try {
    const result = await executeImport(
      entitySlug.value,
      file.value,
      createImportToken(entitySlug.value)
    )
    syncAction.value = 'execute'
    syncReport.value = { title: '执行结果（全量通过才落库）', result }
    if (result.committed) {
      ElMessage.success(`执行完成：新增 ${result.inserted}，更新 ${result.updated}`)
    }
  } catch (error) {
    syncReport.value = null
    syncAction.value = null
    pageError.value = describeFailure(error)
  } finally {
    busy.value = ''
  }
}

function schedulePolling(): void {
  stopPolling()
  pollTimer = window.setTimeout(() => {
    void pollJob()
  }, 1000)
}

async function pollJob(): Promise<void> {
  const current = job.value
  if (!current || !jobEntity.value || !jobActive.value) {
    return
  }
  try {
    job.value = await getImportJob(jobEntity.value, current.jobId)
    if (jobActive.value) {
      schedulePolling()
    }
  } catch (error) {
    pageError.value = describeFailure(error)
  }
}

async function runAsync(): Promise<void> {
  if (!file.value || busy.value) {
    return
  }
  busy.value = 'async'
  pageError.value = ''
  stopPolling()
  syncReport.value = null
  syncAction.value = null
  job.value = null
  jobEntity.value = entitySlug.value
  try {
    job.value = await submitAsyncImport(
      jobEntity.value,
      file.value,
      createImportToken(jobEntity.value)
    )
    ElMessage.success('异步任务已受理（202）')
    if (jobActive.value) {
      schedulePolling()
    }
  } catch (error) {
    job.value = null
    pageError.value = describeFailure(error)
  } finally {
    busy.value = ''
  }
}

async function runCancel(): Promise<void> {
  const current = job.value
  if (!current || !jobEntity.value || busy.value) {
    return
  }
  busy.value = 'cancel'
  pageError.value = ''
  try {
    job.value = await cancelImportJob(jobEntity.value, current.jobId)
    stopPolling()
    ElMessage.success(`任务已取消：${current.jobId}`)
  } catch (error) {
    pageError.value = describeFailure(error)
    // 40901 说明任务已是终态：补拉一次真实状态，避免页面停留在 RUNNING。
    if (error instanceof ApiError && error.code === 40901) {
      try {
        job.value = await getImportJob(jobEntity.value, current.jobId)
        stopPolling()
      } catch {
        // 详情拉取失败时保留 40901 提示，等待用户手动重试。
      }
    }
  } finally {
    busy.value = ''
  }
}

onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="import-page">
    <h2 class="page-title">导入中心</h2>

    <el-alert
      class="page-card"
      type="info"
      :closable="false"
      show-icon
      title="C38 客户导入 / C39 异步大文件导入"
      description="四段流程：下载模板 → 预览校验 → 执行落库 → 导出数据。异步模式返回 202 + ImportJobView 裸对象，页面按 jobId 轮询到终态。"
    />

    <el-card shadow="never" class="page-card">
      <div class="entity-bar">
        <div class="entity-left">
          <span class="field-label">导入实体</span>
          <el-select
            v-model="entitySlug"
            class="entity-select"
            :disabled="jobActive || busy !== ''"
            @change="handleEntityChange"
          >
            <el-option
              v-for="option in IMPORT_ENTITY_OPTIONS"
              :key="option.slug"
              :label="`${option.label}（${option.slug}）`"
              :value="option.slug"
            />
          </el-select>
          <el-tag type="info" effect="plain">{{ entitySlug }}</el-tag>
          <el-tag v-if="entitySlug === 'customers'" type="success" effect="plain">C38</el-tag>
        </div>
        <div class="entity-right">
          <el-button :loading="busy === 'download'" @click="runDownloadTemplate">
            下载模板
          </el-button>
          <el-button :loading="busy === 'download'" @click="runExport">导出当前数据</el-button>
        </div>
      </div>
      <p class="limit-hint">
        后端限制：单文件 ≤ 5MB（{{ IMPORT_MAX_FILE_BYTES }} 字节）、≤ {{ IMPORT_MAX_ROWS }} 行、错误返回 ≤
        200 条。前端仅做扩展名与大小的快速预检，最终以后端 42200 判定为准。
      </p>
    </el-card>

    <el-card shadow="never" class="page-card">
      <el-steps :active="stepActive" simple finish-status="success" class="steps">
        <el-step title="模板下载" />
        <el-step title="预览校验" />
        <el-step title="执行落库" />
        <el-step title="导出数据" />
      </el-steps>

      <div class="mode-bar">
        <span class="field-label">导入模式</span>
        <el-radio-group
          v-model="importMode"
          :disabled="jobActive || busy !== ''"
          @change="handleModeChange"
        >
          <el-radio-button value="sync">同步（预览 → 执行）</el-radio-button>
          <el-radio-button value="async">异步（202 + 轮询）</el-radio-button>
        </el-radio-group>
      </div>

      <el-upload
        ref="uploadRef"
        drag
        class="uploader"
        :auto-upload="false"
        :limit="1"
        accept=".xlsx"
        :disabled="jobActive || busy !== ''"
        :on-change="handleFileChange"
        :on-remove="handleFileRemove"
        :on-exceed="handleExceed"
      >
        <div class="upload-text">将 .xlsx 文件拖到此处，或<em>点击选择</em></div>
        <template #tip>
          <div class="upload-tip">仅支持 .xlsx；前端预检失败时会直接提示，不发起请求。</div>
        </template>
      </el-upload>

      <el-alert
        v-if="precheckError"
        class="precheck"
        type="warning"
        :closable="false"
        show-icon
        :title="precheckError"
      />

      <div v-if="file" class="file-row">
        <span>已选择：{{ file.name }}（{{ formatSize(file.size) }}）</span>
        <el-button link type="danger" :disabled="busy !== ''" @click="clearFile">移除</el-button>
      </div>

      <div class="action-row">
        <template v-if="importMode === 'sync'">
          <el-button
            type="primary"
            :disabled="!file || precheckError !== '' || jobActive"
            :loading="busy === 'preview'"
            @click="runPreview"
          >
            预览（只校验，committed=false）
          </el-button>
          <el-button
            type="success"
            :disabled="!file || precheckError !== '' || jobActive"
            :loading="busy === 'execute'"
            @click="runExecute"
          >
            执行导入（落库）
          </el-button>
        </template>
        <template v-else>
          <el-button
            type="primary"
            :disabled="!file || precheckError !== ''"
            :loading="busy === 'async'"
            @click="runAsync"
          >
            提交异步导入（202）
          </el-button>
        </template>
      </div>
    </el-card>

    <el-card v-if="pageError" shadow="never" class="page-card">
      <el-alert type="error" :closable="false" show-icon :title="pageError" />
    </el-card>

    <el-card v-if="syncReport" shadow="never" class="page-card">
      <template #header>
        <div class="card-header">
          <span>{{ syncReport.title }}</span>
          <el-tag :type="syncReport.result.committed ? 'success' : 'info'" effect="plain">
            committed={{ syncReport.result.committed ? 'true' : 'false' }}
          </el-tag>
        </div>
      </template>

      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="实体">{{ syncReport.result.entity }}</el-descriptions-item>
        <el-descriptions-item label="模式">{{ syncReport.result.mode }}</el-descriptions-item>
        <el-descriptions-item label="幂等重放">
          {{ syncReport.result.replayed ? '是（replayed=true）' : '否' }}
        </el-descriptions-item>
        <el-descriptions-item label="总行数">{{ syncReport.result.totalRows }}</el-descriptions-item>
        <el-descriptions-item label="通过行数">{{ syncReport.result.validRows }}</el-descriptions-item>
        <el-descriptions-item label="失败行数">{{ syncReport.result.invalidRows }}</el-descriptions-item>
        <el-descriptions-item label="新增">{{ syncReport.result.inserted }}</el-descriptions-item>
        <el-descriptions-item label="更新">{{ syncReport.result.updated }}</el-descriptions-item>
        <el-descriptions-item label="跳过">{{ syncReport.result.skipped }}</el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="!syncReport.result.committed"
        class="result-alert"
        type="warning"
        :closable="false"
        show-icon
        title="committed=false：整批未落库"
        description="存在校验错误时整批不落库；错误行按 Excel 物理行号与列键定位，见下表。"
      />

      <h4 class="section-title">错误行（{{ syncReport.result.errors.length }}）</h4>
      <ImportErrorTable :errors="syncReport.result.errors" :entity="syncReport.result.entity" />

      <h4 class="section-title">规范化样例（最多 20 行）</h4>
      <el-empty v-if="syncReport.result.preview.length === 0" :image-size="56" description="无样例行" />
      <el-table v-else :data="syncReport.result.preview" size="small" border max-height="360">
        <el-table-column type="index" label="#" width="56" />
        <el-table-column
          v-for="column in entityColumns"
          :key="column.key"
          :label="column.header"
          min-width="140"
        >
          <template #default="{ row }">{{ formatCell(row[column.key]) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="job" shadow="never" class="page-card">
      <template #header>
        <div class="card-header">
          <span>异步任务（{{ jobEntity }}）</span>
          <div class="card-header-right">
            <el-tag :type="jobTagType(job.status)" effect="dark">
              {{ job.status }} · {{ jobStatusLabel(job.status) }}
            </el-tag>
            <el-button
              type="danger"
              plain
              size="small"
              :loading="busy === 'cancel'"
              @click="runCancel"
            >
              取消任务
            </el-button>
          </div>
        </div>
      </template>

      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="jobId" :span="2">{{ job.jobId }}</el-descriptions-item>
        <el-descriptions-item label="mode">{{ job.mode }}</el-descriptions-item>
        <el-descriptions-item label="总行数">{{ job.totalRows }}</el-descriptions-item>
        <el-descriptions-item label="已处理">{{ job.processedRows }}</el-descriptions-item>
        <el-descriptions-item label="失败行数">{{ job.invalidRows }}</el-descriptions-item>
        <el-descriptions-item label="新增">{{ job.inserted }}</el-descriptions-item>
        <el-descriptions-item label="更新">{{ job.updated }}</el-descriptions-item>
        <el-descriptions-item label="跳过">{{ job.skipped }}</el-descriptions-item>
        <el-descriptions-item label="committed" :span="3">
          <el-tag :type="job.committed ? 'success' : 'info'" effect="plain">
            {{ job.committed ? 'true（已落库）' : 'false（未落库）' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="contentHash" :span="3">
          <span class="mono">{{ job.contentHash }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="createdAt">{{ job.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="startedAt">{{ job.startedAt ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="finishedAt">{{ job.finishedAt ?? '—' }}</el-descriptions-item>
      </el-descriptions>

      <div class="progress-row">
        <el-progress
          :percentage="jobPercent"
          :status="jobProgressStatus"
          :stroke-width="14"
          :indeterminate="jobActive && job.totalRows <= 0"
        />
        <span class="progress-tip">
          {{
            jobActive
              ? '轮询中（每 1s 查询一次，直到 PENDING/RUNNING 之外的终态）'
              : '已到终态，停止轮询'
          }}
        </span>
      </div>

      <h4 class="section-title">错误行（{{ job.errors.length }}）</h4>
      <ImportErrorTable :errors="job.errors" :entity="jobEntity" />
    </el-card>

    <el-card shadow="never" class="page-card">
      <template #header>错误码映射（语义不改，仅叠加可读提示）</template>
      <el-table :data="ERROR_MAPPING" size="small" border>
        <el-table-column label="错误码" prop="code" width="110" />
        <el-table-column label="含义" prop="meaning" width="180" />
        <el-table-column label="页面处理" prop="behavior" min-width="320" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.import-page {
  padding-bottom: var(--space-6);
}

.entity-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.entity-left,
.entity-right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.entity-select {
  width: 220px;
}

.field-label {
  color: var(--text-2);
  font-size: var(--font-sm);
}

.limit-hint {
  margin: var(--space-3) 0 0;
  color: var(--text-3);
  font-size: var(--font-xs);
  line-height: 1.7;
}

.steps {
  margin-bottom: var(--space-4);
}

.mode-bar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.uploader {
  margin-top: var(--space-2);
}

.upload-text {
  color: var(--text-2);
}

.upload-text em {
  color: var(--primary);
  font-style: normal;
}

.upload-tip {
  color: var(--text-3);
  font-size: var(--font-xs);
}

.precheck {
  margin-top: var(--space-3);
}

.file-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
  color: var(--text-2);
  font-size: var(--font-sm);
}

.action-row {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-4);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.card-header-right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.section-title {
  margin: var(--space-4) 0 var(--space-2);
  color: var(--text);
  font-size: var(--font-base);
  font-weight: 600;
}

.result-alert {
  margin-top: var(--space-3);
}

.progress-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.progress-tip {
  color: var(--text-3);
  font-size: var(--font-xs);
}

.mono {
  font-family: var(--font-mono, monospace);
  font-size: var(--font-xs);
  word-break: break-all;
}
</style>
