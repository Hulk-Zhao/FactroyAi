<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { useRoute } from 'vue-router'

import {
  getPlanVersionDetail,
  listPlanVersions,
  publishPlanVersion,
  rollbackPlanVersion
} from '@/api/plans'
import { ApiError } from '@/api/http'
import { listSandboxes } from '@/api/orders'
import { listResources } from '@/api/resources'
import { DataTable, StatusTag } from '@/components/common'
import { ERROR_CODES } from '@/constants/error-codes'
import { DEFAULT_SANDBOX_ID } from '@/constants/defaults'
import type { PlanAction, PlanDetail, PlanListItem } from '@/types/plans'
import type { Sandbox } from '@/types/orders'
import type { Resource } from '@/types/resources'

const PLAN_ID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

const route = useRoute()

const sandboxId = ref(DEFAULT_SANDBOX_ID)
const listRows = ref<PlanListItem[]>([])
const listPage = ref(1)
const listSize = ref(20)
const listTotal = ref(0)
const listLoading = ref(false)
const listError = ref('')

const planIdInput = ref('')
const sandboxMap = ref<Record<string, Sandbox>>({})
const resourceMap = ref<Record<string, Resource>>({})
const detail = ref<PlanDetail | null>(null)
const loading = ref(false)
const loadError = ref('')
const conflictMessage = ref('')

const publishReason = ref('')
const approver = ref('')
const publishing = ref(false)

const rollbackTarget = ref<number | null>(null)
const rollbackReason = ref('')
const rollingBack = ref(false)

interface ActionLogRow {
  key: string
  at: string
  action: string
  ok: boolean
  planId: string
  version: number | null
  message: string
}

const actionLog = ref<ActionLogRow[]>([])
let rowSeq = 0

const currentAction = computed<PlanAction | null>(() => detail.value?.action ?? null)
const snapshotTasks = computed(() => detail.value?.tasks ?? [])
const snapshotMismatch = computed(() => {
  const taskCount = currentAction.value?.taskCount
  return taskCount !== undefined && taskCount !== null && taskCount !== snapshotTasks.value.length
})
const isPublished = computed(() => currentAction.value?.status === 'PUBLISHED')

function nowText(): string {
  return dayjs().format('YYYY-MM-DD HH:mm:ss')
}

function formatDateTime(value?: string | null): string {
  if (!value) {
    return '无'
  }
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : '无'
}

function displayOrDash(value?: string | null): string {
  return value && String(value).trim() ? String(value) : '无'
}

function sandboxLabel(id?: string | null): string {
  if (!id) {
    return '无'
  }
  return sandboxMap.value[id]?.name || '场景名称待接入'
}

function rollbackSourceLabel(planId?: string | null): string {
  if (!planId) {
    return '无'
  }
  const matched = listRows.value.find((item) => item.planId === planId)
  return matched ? matched.planNo : '历史版本'
}

function resourceLabel(id?: string | null): string {
  if (!id) {
    return '无'
  }
  const resource = resourceMap.value[id]
  return resource ? `${resource.name}（${resource.code}）` : '资源名称待接入'
}

async function loadLookups() {
  try {
    const page = await listSandboxes({ page: 1, size: 100 })
    const map: Record<string, Sandbox> = {}
    page.items.forEach((item) => {
      map[item.id] = item
    })
    sandboxMap.value = map
  } catch {
    // 场景名称不可用时退回业务提示
  }
  try {
    const page = await listResources({ page: 1, size: 200, sort: 'code,asc' })
    const map: Record<string, Resource> = {}
    page.items.forEach((item) => {
      map[item.id] = item
    })
    resourceMap.value = map
  } catch {
    // 资源名称不可用时退回业务提示
  }
}

async function loadPlans() {
  const id = sandboxId.value.trim()
  listError.value = ''
  if (!PLAN_ID_PATTERN.test(id)) {
    listError.value = '排产场景无效，请重新选择'
    listRows.value = []
    listTotal.value = 0
    return
  }
  listLoading.value = true
  try {
    const result = await listPlanVersions({
      sandboxId: id,
      page: listPage.value,
      size: listSize.value
    })
    listRows.value = result.items
    listTotal.value = result.total
    listPage.value = result.page
    listSize.value = result.size
  } catch (exception) {
    listRows.value = []
    listTotal.value = 0
    listError.value = exception instanceof Error ? exception.message : '版本列表加载失败'
  } finally {
    listLoading.value = false
  }
}

function searchPlans() {
  listPage.value = 1
  void loadPlans()
}

function pushLog(
  action: string,
  ok: boolean,
  planId: string,
  message: string,
  version?: number | null
) {
  rowSeq += 1
  actionLog.value.unshift({
    key: `log-${rowSeq}`,
    at: nowText(),
    action,
    ok,
    planId,
    version: version ?? null,
    message
  })
}

function handleActionError(exception: unknown, actionLabel: string, planId: string) {
  const message = exception instanceof Error ? exception.message : `${actionLabel}失败`
  if (exception instanceof ApiError && exception.code === ERROR_CODES.PUBLISHED_CONFLICT) {
    conflictMessage.value = message
  } else {
    loadError.value = message
  }
  pushLog(actionLabel, false, planId, message)
}

async function loadDetail(planId = planIdInput.value) {
  const id = planId.trim()
  loadError.value = ''
  conflictMessage.value = ''
  if (!id) {
    loadError.value = '请选择要加载的计划版本'
    return
  }
  if (!PLAN_ID_PATTERN.test(id)) {
    loadError.value = '计划标识格式不正确，请从版本列表重新选择'
    detail.value = null
    return
  }
  loading.value = true
  try {
    const result = await getPlanVersionDetail(id)
    detail.value = result
    planIdInput.value = id
  } catch (exception) {
    detail.value = null
    loadError.value = exception instanceof Error ? exception.message : '版本详情加载失败'
  } finally {
    loading.value = false
  }
}

async function onPublish() {
  const action = currentAction.value
  if (!action) {
    ElMessage.warning('请先加载版本详情')
    return
  }
  if (action.status === 'ARCHIVED') {
    ElMessage.warning('已归档版本不可发布（后端 40901）')
    return
  }
  publishing.value = true
  conflictMessage.value = ''
  loadError.value = ''
  try {
    const published = await publishPlanVersion(action.planId, {
      reason: publishReason.value,
      approver: approver.value
    })
    const idempotent = action.status === 'PUBLISHED'
    pushLog(
      '发布',
      true,
      published.planId,
      idempotent
        ? '版本已是 PUBLISHED，后端幂等返回 200，未重复固化快照'
        : `快照已固化，回填任务 ${published.affectedTaskCount} 条`,
      published.version
    )
    ElMessage.success(
      idempotent
        ? '版本已是 PUBLISHED，后端幂等返回 200'
        : `发布成功：${published.planNo ?? published.planId}（v${published.version}）`
    )
    await loadDetail(published.planId)
    await loadPlans()
  } catch (exception) {
    handleActionError(exception, '发布', action.planId)
  } finally {
    publishing.value = false
  }
}

async function onRollback() {
  const action = currentAction.value
  if (!action) {
    ElMessage.warning('请先加载版本详情')
    return
  }
  const target = rollbackTarget.value
  if (target === null || !Number.isInteger(target) || target < 1) {
    ElMessage.warning('目标版本号必须是 ≥1 的整数')
    return
  }
  if (target === action.version) {
    ElMessage.warning('目标版本号与当前版本相同，后端会以 40000 拒绝')
    return
  }
  rollingBack.value = true
  conflictMessage.value = ''
  loadError.value = ''
  try {
    const created = await rollbackPlanVersion(action.planId, {
      targetVersionNo: target,
      reason: rollbackReason.value
    })
    pushLog(
      '回滚',
      true,
      created.planId,
      `新版本 ${created.planNo ?? created.planId}（v${created.version}）重指向任务 ${created.affectedTaskCount} 条；源版本快照未改动`,
      created.version
    )
    ElMessage.success(`回滚成功：新版本 ${created.planNo ?? created.planId}（v${created.version}）`)
    planIdInput.value = created.planId
    await loadDetail(created.planId)
    await loadPlans()
  } catch (exception) {
    handleActionError(exception, '回滚', action.planId)
  } finally {
    rollingBack.value = false
  }
}

onMounted(() => {
  void loadLookups()
  void loadPlans()
  const queryPlanId = route.query.planId
  const initial = typeof queryPlanId === 'string' ? queryPlanId : ''
  planIdInput.value = initial
  if (initial) {
    void loadDetail(initial)
  }
})
</script>

<template>
  <div>
    <h2 class="page-title">计划发布与版本</h2>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="列表固定按发布时间倒序排列，不支持自定义排序"
      description="未发布的草稿/审核中版本排在最前，其后是按发布时间倒序的已发布版本。审批人与发布人是两个独立字段；审批人为空时显示「无」，不用发布人顶替。"
      class="hint-alert"
    />

    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-header">
          <span>版本列表</span>
          <span class="muted">按排产场景筛选计划版本</span>
        </div>
      </template>
      <div class="form-row list-toolbar">
        <el-select
          v-if="Object.keys(sandboxMap).length"
          v-model="sandboxId"
          filterable
          placeholder="选择排产场景"
          class="plan-id-input"
        >
          <el-option
            v-for="item in Object.values(sandboxMap)"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
        <el-input
          v-else
          v-model="sandboxId"
          placeholder="场景 ID"
          clearable
          class="plan-id-input"
          @keyup.enter="searchPlans"
        />
        <el-button type="primary" :loading="listLoading" @click="searchPlans">查询版本</el-button>
        <el-button :disabled="listLoading" @click="loadPlans()">刷新</el-button>
      </div>
      <DataTable
        v-model:page="listPage"
        v-model:size="listSize"
        :data="listRows"
        :loading="listLoading"
        :error="listError"
        :total="listTotal"
        row-key="planId"
        empty-text="该场景暂无计划版本"
        @change="loadPlans"
        @retry="loadPlans"
      >
        <el-table-column prop="planNo" label="计划号" min-width="170" />
        <el-table-column label="版本号" width="90">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusTag enum-key="planStatus" :value="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="发布时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="发布人" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ displayOrDash(row.publishedBy) }}</template>
        </el-table-column>
        <el-table-column label="审批人" min-width="150">
          <template #default="{ row }">{{ displayOrDash(row.approver) }}</template>
        </el-table-column>
        <el-table-column prop="taskCount" label="快照任务数" width="110" />
        <el-table-column label="回滚来源" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ rollbackSourceLabel(row.rollbackOf) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="loadDetail(row.planId)">查看详情</el-button>
          </template>
        </el-table-column>
      </DataTable>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>加载版本</template>
      <div class="form-row">
        <el-select
          v-if="listRows.length"
          v-model="planIdInput"
          filterable
          placeholder="选择计划版本"
          class="plan-id-input"
        >
          <el-option
            v-for="row in listRows"
            :key="row.planId"
            :label="`${row.planNo}（v${row.version}）`"
            :value="row.planId"
          />
        </el-select>
        <el-input
          v-else
          v-model="planIdInput"
          placeholder="计划标识"
          clearable
          class="plan-id-input"
          @keyup.enter="loadDetail()"
        />
        <el-button type="primary" :loading="loading" @click="loadDetail()">加载详情</el-button>
      </div>
      <p class="field-hint">
        请从当前场景的计划版本中选择；场景不能直接作为计划标识（C24 第 6 条）。
      </p>
    </el-card>

    <el-alert
      v-if="conflictMessage"
      type="error"
      :closable="false"
      show-icon
      title="40903 · 已发布的计划/任务不可回滚或覆盖"
      :description="conflictMessage"
      class="section"
    />

    <el-card v-if="loadError" shadow="never" class="section">
      <el-alert type="error" :closable="false" :title="loadError" />
    </el-card>

    <el-card v-if="currentAction" shadow="never" class="section">
      <template #header>
        <div class="card-header">
          <span>版本详情</span>
          <StatusTag enum-key="planStatus" :value="currentAction.status" />
        </div>
      </template>

      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="计划号">
          {{ displayOrDash(currentAction.planNo) }}
        </el-descriptions-item>
        <el-descriptions-item label="版本号">v{{ currentAction.version }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag enum-key="planStatus" :value="currentAction.status" />
        </el-descriptions-item>
        <el-descriptions-item label="排产场景">
          {{ sandboxLabel(currentAction.sandboxId) }}
        </el-descriptions-item>
        <el-descriptions-item label="快照任务数">
          {{ currentAction.taskCount ?? '无' }}
        </el-descriptions-item>
        <el-descriptions-item label="发布时间">
          {{ formatDateTime(currentAction.publishedAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="审批人">
          {{ displayOrDash(currentAction.approver) }}
        </el-descriptions-item>
        <el-descriptions-item label="回滚来源">
          {{ displayOrDash(currentAction.rollbackOf) }}
        </el-descriptions-item>
        <el-descriptions-item label="原因" :span="3">
          {{ displayOrDash(currentAction.reason) }}
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="snapshotMismatch"
        type="warning"
        :closable="false"
        show-icon
        class="inline-alert"
        :title="`taskCount=${currentAction.taskCount} 与快照实际 ${snapshotTasks.length} 条不一致，请核对后端快照`"
      />
    </el-card>

    <el-card v-if="currentAction" shadow="never" class="section">
      <template #header>
        <div class="card-header">
          <span>任务快照</span>
          <span class="muted"
            >共 {{ snapshotTasks.length }} 条 · 来自发布时刻的 snapshot，不是当前 task 表</span
          >
        </div>
      </template>
      <DataTable
        :data="snapshotTasks"
        :show-pagination="false"
        row-key="taskId"
        empty-text="该版本尚无快照（未发布时 snapshot 为空）"
      >
        <el-table-column prop="taskId" label="任务 ID" width="110" />
        <el-table-column prop="orderNo" label="订单号" min-width="140" />
        <el-table-column prop="operationIndex" label="工序号" width="90" />
        <el-table-column label="任务状态" width="110">
          <template #default="{ row }">
            <StatusTag enum-key="taskStatus" :value="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="开始" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="结束" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="资源" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ resourceLabel(row.resourceId) }}</template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="100" />
      </DataTable>
    </el-card>

    <el-card v-if="currentAction" shadow="never" class="section">
      <template #header>发布</template>
      <div class="form-grid">
        <div class="form-field">
          <label class="field-label" for="plan-publish-reason">发布原因 reason</label>
          <el-input
            id="plan-publish-reason"
            v-model="publishReason"
            maxlength="500"
            show-word-limit
            placeholder="可选"
          />
        </div>
        <div class="form-field">
          <label class="field-label" for="plan-approver">审批人 approver</label>
          <el-input
            id="plan-approver"
            v-model="approver"
            maxlength="64"
            placeholder="可选；留空则不记录审批人（落 NULL）"
          />
        </div>
        <div class="form-field form-actions">
          <el-button type="primary" :loading="publishing" @click="onPublish">
            {{ isPublished ? '再次发布（幂等）' : '发布版本' }}
          </el-button>
        </div>
      </div>
      <p class="field-hint">
        空白 approver 会原样落库为 NULL，前端不会用当前登录用户回填。MES 下发为占位（50100），M2
        不做外部推送。
      </p>
    </el-card>

    <el-card v-if="currentAction" shadow="never" class="section">
      <template #header>回滚</template>
      <div class="form-grid">
        <div class="form-field">
          <label class="field-label" for="plan-rollback-target">目标版本号 targetVersionNo</label>
          <el-input-number
            id="plan-rollback-target"
            v-model="rollbackTarget"
            :min="1"
            :step="1"
            step-strictly
            controls-position="right"
          />
        </div>
        <div class="form-field">
          <label class="field-label" for="plan-rollback-reason">回滚原因 reason</label>
          <el-input
            id="plan-rollback-reason"
            v-model="rollbackReason"
            maxlength="500"
            show-word-limit
            placeholder="可选"
          />
        </div>
        <div class="form-field form-actions">
          <el-button type="warning" :loading="rollingBack" @click="onRollback"
            >回滚到该版本</el-button
          >
        </div>
      </div>
      <p class="field-hint">
        回滚会新建 PUBLISHED 版本（rollback_of=当前版本）并复制目标快照，旧版本原封不动；场景内存在
        IN_PROGRESS/COMPLETED 任务时后端返回 40903。
      </p>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>操作留痕（本次会话）</template>
      <DataTable
        :data="actionLog"
        :show-pagination="false"
        row-key="key"
        empty-text="尚未执行发布/回滚"
      >
        <el-table-column prop="at" label="时间" width="170" />
        <el-table-column prop="action" label="操作" width="90" />
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.ok ? 'success' : 'danger'" size="small">
              {{ row.ok ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本号" width="90">
          <template #default="{ row }">{{
            row.version === null ? '无' : `v${row.version}`
          }}</template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="320" />
      </DataTable>
    </el-card>
  </div>
</template>

<style scoped>
.hint-alert {
  margin-bottom: var(--space-4);
}

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
  color: var(--el-text-color-secondary);
  font-size: var(--font-xs);
  font-weight: 400;
}

.form-row {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}

.plan-id-input {
  max-width: 460px;
}

.list-toolbar {
  margin-bottom: var(--space-3);
}

.form-grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  align-items: flex-end;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 260px;
  flex: 1;
}

.form-actions {
  flex: 0 0 auto;
  min-width: 0;
}

.field-label {
  font-size: var(--font-xs);
  color: var(--el-text-color-regular);
}

.field-hint {
  margin: var(--space-2) 0 0;
  font-size: var(--font-xs);
  color: var(--el-text-color-secondary);
}

.inline-alert {
  margin-top: var(--space-3);
}
</style>
