<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { listCalendars } from '@/api/calendars'
import { deactivateResource, listResources } from '@/api/resources'
import { DataTable, StatusTag } from '@/components/common'
import { getEnumOptions } from '@/constants/enums'
import type { Calendar } from '@/types/calendars'
import type { Resource, ResourceType } from '@/types/resources'

import ResourceFormDialog from './ResourceFormDialog.vue'
import { computeCapacity, formatSeconds } from './capacity'

const resourceTypeOptions = getEnumOptions('resourceType')

const filters = reactive<{
  code: string
  name: string
  type: ResourceType | ''
  groupId: string
}>({ code: '', name: '', type: '', groupId: '' })

const rows = ref<Resource[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

const formVisible = ref(false)
const editingResource = ref<Resource | null>(null)
const calendarMap = ref<Record<string, Calendar>>({})

async function loadCalendarOptions() {
  try {
    const result = await listCalendars({ page: 1, size: 200 })
    const map: Record<string, Calendar> = {}
    result.items.forEach((item) => {
      map[item.id] = item
    })
    calendarMap.value = map
  } catch {
    // 日历加载失败不阻塞资源列表
  }
}

async function loadResources() {
  loading.value = true
  error.value = ''
  try {
    const result = await listResources({
      page: page.value,
      size: size.value,
      sort: 'code,asc',
      code: filters.code.trim() || undefined,
      name: filters.name.trim() || undefined,
      type: filters.type || undefined,
      groupId: filters.groupId.trim() || undefined
    })
    rows.value = result.items
    total.value = result.total
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '资源加载失败'
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void loadResources()
}

function resetFilters() {
  filters.code = ''
  filters.name = ''
  filters.type = ''
  filters.groupId = ''
  search()
}

function openCreate() {
  editingResource.value = null
  formVisible.value = true
}

function openEdit(resource: Resource) {
  editingResource.value = resource
  formVisible.value = true
}

function onSaved() {
  void loadResources()
}

function calendarLabel(calendarId?: string | null): string {
  if (!calendarId) {
    return '无'
  }
  const calendar = calendarMap.value[calendarId]
  return calendar ? `${calendar.name} ${calendar.calendarVersion}` : '日历名称待接入'
}

function groupLabel(groupId?: string | null): string {
  return groupId ? '已分组' : '未分组'
}

function setupText(resource: Resource): string {
  return resource.setupSeconds === undefined || resource.setupSeconds === null
    ? '无'
    : `${resource.setupSeconds} 秒`
}

function capacitySummary(resource: Resource): string {
  const efficiency = resource.efficiency ?? 1
  switch (resource.capacityMode) {
    case 'UNIT_PER_HOUR':
      return `${resource.capacityValue} 件/小时 × 效率 ${efficiency}`
    case 'SECONDS_PER_UNIT':
      return `标准工时 ÷ 效率 ${efficiency}`
    case 'BATCH_SECONDS':
      return `每批 ${resource.capacityValue} 秒 × ${resource.batchCapacity ?? '无'} 件/批，效率 ${efficiency}`
    default:
      return '无'
  }
}

function perUnitSummary(resource: Resource): string {
  if (resource.capacityMode === 'SECONDS_PER_UNIT') {
    return '按工序标准工时'
  }
  const result = computeCapacity({
    capacityMode: resource.capacityMode,
    capacityValue: resource.capacityValue,
    batchCapacity: resource.batchCapacity,
    efficiency: resource.efficiency
  })
  return result.valid ? formatSeconds(result.perUnitSeconds) : result.message
}

function isActive(resource: Resource): boolean {
  return (resource.status ?? 'ACTIVE') === 'ACTIVE'
}

async function deactivate(resource: Resource) {
  try {
    await ElMessageBox.confirm(
      `停用资源「${resource.code}」后将从列表隐藏（软删除），历史排产任务保留；若被工艺路线或日历引用，后端会返回 42200。是否继续？`,
      '停用资源',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deactivateResource(resource.code)
    ElMessage.success('资源已停用')
    await loadResources()
  } catch {
    // 统一拦截器已提示，保留列表以便重试
  }
}

onMounted(() => {
  void loadCalendarOptions()
  void loadResources()
})
</script>

<template>
  <div>
    <h2 class="page-title">资源管理</h2>

    <el-alert
      class="page-alert"
      type="info"
      :closable="false"
      show-icon
      title="产能口径按契约 C2：件/小时 = cap × eff；秒/件 = 标准工时 ÷ eff；批次秒 = ceil(qty/batch) × cap ÷ eff，并追加换型工时。"
    />

    <el-card shadow="never" class="page-card">
      <el-form :inline="true" @submit.prevent="search">
        <el-form-item label="资源编码">
          <el-input
            v-model="filters.code"
            clearable
            placeholder="按资源编码模糊查询"
            style="width: 170px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item label="资源名称">
          <el-input
            v-model="filters.name"
            clearable
            placeholder="按资源名称模糊查询"
            style="width: 180px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item label="资源类型">
          <el-select v-model="filters.type" clearable placeholder="全部" style="width: 130px">
            <el-option
              v-for="item in resourceTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="资源组">
          <el-input
            v-model="filters.groupId"
            clearable
            placeholder="资源组 ID"
            style="width: 210px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :data="rows"
      :loading="loading"
      :error="error"
      :total="total"
      row-key="code"
      empty-text="暂无资源数据"
      @change="loadResources"
      @retry="loadResources"
    >
      <template #toolbar>
        <el-button type="primary" @click="openCreate">新建资源</el-button>
      </template>

      <el-table-column prop="name" label="资源名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="code" label="资源编码" min-width="160">
        <template #default="{ row }">
          <span class="secondary-text">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column label="资源类型" width="110">
        <template #default="{ row }">
          <StatusTag enum-key="resourceType" :value="row.type" />
        </template>
      </el-table-column>
      <el-table-column label="产能模式" width="110">
        <template #default="{ row }">
          <StatusTag enum-key="capacityMode" :value="row.capacityMode" />
        </template>
      </el-table-column>
      <el-table-column label="产能" min-width="230" show-overflow-tooltip>
        <template #default="{ row }">{{ capacitySummary(row) }}</template>
      </el-table-column>
      <el-table-column label="节拍（秒/件）" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ perUnitSummary(row) }}</template>
      </el-table-column>
      <el-table-column label="换型时间" width="110">
        <template #default="{ row }">{{ setupText(row) }}</template>
      </el-table-column>
      <el-table-column label="工作日历" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ calendarLabel(row.calendarId) }}</template>
      </el-table-column>
      <el-table-column label="资源组" width="110">
        <template #default="{ row }">
          <el-tooltip v-if="row.groupId" content="资源组名称待后端接口补齐" placement="top">
            <span>{{ groupLabel(row.groupId) }}</span>
          </el-tooltip>
          <span v-else class="none-text">未分组</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="isActive(row) ? 'success' : 'info'" size="small">
            {{ isActive(row) ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" :disabled="!isActive(row)" @click="deactivate(row)">
            停用
          </el-button>
        </template>
      </el-table-column>
    </DataTable>

    <ResourceFormDialog v-model="formVisible" :resource="editingResource" @saved="onSaved" />
  </div>
</template>

<style scoped>
.page-alert {
  margin-bottom: 12px;
}

.secondary-text {
  color: var(--text-3);
  font-size: var(--font-xs);
}

.none-text {
  color: var(--text-3);
}
</style>
