<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'

import { listCalendars } from '@/api/calendars'
import { DataTable, StatusTag } from '@/components/common'
import { getEnumOptions } from '@/constants/enums'
import type { Calendar, CalendarType } from '@/types/calendars'

import CalendarFormDialog from './CalendarFormDialog.vue'
import { calendarLifecycle, lifecycleLabel, lifecycleTagType } from './lifecycle'

const calendarTypeOptions = getEnumOptions('calendarType')

const filters = reactive<{ name: string; type: CalendarType | '' }>({ name: '', type: '' })

const rows = ref<Calendar[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

const formVisible = ref(false)
const editingCalendar = ref<Calendar | null>(null)
const copyMode = ref(false)

async function loadCalendars() {
  loading.value = true
  error.value = ''
  try {
    const result = await listCalendars({
      page: page.value,
      size: size.value,
      sort: 'effectiveFrom,desc',
      name: filters.name.trim() || undefined,
      type: filters.type || undefined
    })
    rows.value = result.items
    total.value = result.total
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '日历加载失败'
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void loadCalendars()
}

function resetFilters() {
  filters.name = ''
  filters.type = ''
  search()
}

function openCreate() {
  editingCalendar.value = null
  copyMode.value = false
  formVisible.value = true
}

function openCopy(calendar: Calendar) {
  editingCalendar.value = calendar
  copyMode.value = true
  formVisible.value = true
}

async function openEdit(calendar: Calendar) {
  const lifecycle = calendarLifecycle(calendar)
  if (lifecycle === 'HISTORICAL') {
    return
  }
  if (lifecycle === 'EFFECTIVE') {
    try {
      await ElMessageBox.confirm(
        `日历「${calendar.name} ${calendar.calendarVersion}」正在生效，修改会影响后续排程的可用工时。PRD 建议通过新版本覆盖，是否仍直接编辑？`,
        '编辑生效中日历',
        { type: 'warning', confirmButtonText: '继续编辑', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  editingCalendar.value = calendar
  copyMode.value = false
  formVisible.value = true
}

function onSaved() {
  void loadCalendars()
}

function lifecycleOf(calendar: Calendar) {
  return calendarLifecycle(calendar)
}

function shiftSummary(calendar: Calendar): string {
  if (!calendar.shifts || calendar.shifts.length === 0) {
    return '无班次'
  }
  let weeklyHours = 0
  calendar.shifts.forEach((shift) => {
    const [startHour, startMinute] = shift.startTime.split(':').map(Number)
    const [endHour, endMinute] = shift.endTime.split(':').map(Number)
    const start = startHour * 60 + startMinute
    const end = endHour * 60 + endMinute
    const duration = end > start ? end - start : 1440 - start + end
    weeklyHours += (duration / 60) * shift.daysOfWeek.length
  })
  return `${calendar.shifts.length} 个班次 / 约 ${weeklyHours.toFixed(1)} 小时/周`
}

function holidaySummary(calendar: Calendar): string {
  return `${(calendar.holidays ?? []).length} 条`
}

function effectiveRange(calendar: Calendar): string {
  return `${calendar.effectiveFrom} ~ ${calendar.effectiveTo || '长期'}`
}

onMounted(loadCalendars)
</script>

<template>
  <div>
    <h2 class="page-title">日历管理</h2>

    <el-alert
      class="page-alert"
      type="info"
      :closable="false"
      show-icon
      title="日历按版本管理：历史日历只读，变更请复制为新版本；生效中日历修改会影响后续排程。"
    />

    <el-card shadow="never" class="page-card">
      <el-form :inline="true" @submit.prevent="search">
        <el-form-item label="名称">
          <el-input
            v-model="filters.name"
            clearable
            placeholder="按名称模糊查询"
            style="width: 220px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="filters.type" clearable placeholder="全部" style="width: 140px">
            <el-option
              v-for="item in calendarTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
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
      row-key="id"
      empty-text="暂无日历数据"
      @change="loadCalendars"
      @retry="loadCalendars"
    >
      <template #toolbar>
        <el-button type="primary" @click="openCreate">新建日历</el-button>
      </template>

      <el-table-column prop="name" label="日历名称" min-width="180" show-overflow-tooltip />
      <el-table-column prop="calendarVersion" label="版本" width="100" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <StatusTag enum-key="calendarType" :value="row.type" />
        </template>
      </el-table-column>
      <el-table-column label="生效区间" min-width="200">
        <template #default="{ row }">{{ effectiveRange(row) }}</template>
      </el-table-column>
      <el-table-column label="班次结构" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ shiftSummary(row) }}</template>
      </el-table-column>
      <el-table-column label="例外日期" width="100">
        <template #default="{ row }">{{ holidaySummary(row) }}</template>
      </el-table-column>
      <el-table-column label="生效状态" width="120">
        <template #default="{ row }">
          <el-tag :type="lifecycleTagType(lifecycleOf(row))" size="small">
            {{ lifecycleLabel(lifecycleOf(row)) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="乐观锁版本" prop="version" width="110" />
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :disabled="lifecycleOf(row) === 'HISTORICAL'"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
          <el-button link type="warning" @click="openCopy(row)">复制为新版本</el-button>
        </template>
      </el-table-column>
    </DataTable>

    <CalendarFormDialog
      v-model="formVisible"
      :calendar="editingCalendar"
      :copy-mode="copyMode"
      @saved="onSaved"
    />
  </div>
</template>

<style scoped>
.page-alert {
  margin-bottom: 12px;
}
</style>
