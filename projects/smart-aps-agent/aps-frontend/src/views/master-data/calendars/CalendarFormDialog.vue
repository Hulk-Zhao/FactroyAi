<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormItemRule, FormRules } from 'element-plus'

import { createCalendar, updateCalendar } from '@/api/calendars'
import { createIdempotencyKey } from '@/api/http'
import { getEnumOptions } from '@/constants/enums'
import { isoDate, maxLength, required } from '@/utils/form-rules'
import type {
  Calendar,
  CalendarCreateRequest,
  CalendarType,
  Holiday,
  HolidayKind,
  Shift
} from '@/types/calendars'

import ShiftTimeline from './ShiftTimeline.vue'
import { calendarLifecycle, lifecycleLabel, nextCalendarVersion, today } from './lifecycle'

interface CalendarFormModel {
  name: string
  calendarVersion: number
  type: CalendarType
  resourceId: string
  resourceGroupId: string
  effectiveFrom: string
  effectiveTo: string
  shifts: Shift[]
  holidays: Holiday[]
}

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    calendar?: Calendar | null
    /** 历史日历「复制为新版本」：创建新记录而非修改原记录。 */
    copyMode?: boolean
  }>(),
  {
    calendar: null,
    copyMode: false
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [calendar: Calendar]
}>()

const calendarTypeOptions = getEnumOptions('calendarType')
const dayOptions = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' }
]
const holidayKindOptions: { value: HolidayKind; label: string }[] = [
  { value: 'LEGAL', label: '法定节假日' },
  { value: 'COMPANY', label: '公司假日' },
  { value: 'MAKEUP', label: '调休上班' }
]
const timeSelectProps = { start: '00:00', end: '23:45', step: '00:15' }

const formRef = ref<FormInstance>()
const submitting = ref(false)
const activeTab = ref<'visual' | 'json'>('visual')
const jsonText = ref('')
const jsonError = ref('')
const form = reactive<CalendarFormModel>(createEmptyForm())

const title = computed(() => {
  if (props.copyMode) {
    return '复制为新版本'
  }
  return props.calendar ? '编辑日历' : '新建日历'
})
const sourceLifecycle = computed(() => (props.calendar ? calendarLifecycle(props.calendar) : null))
const isHistoricalSource = computed(() => sourceLifecycle.value === 'HISTORICAL')
const isEffectiveSource = computed(() => sourceLifecycle.value === 'EFFECTIVE')
const isFactoryCalendar = computed(() => form.type === 'FACTORY')

const optionalUuidRule: FormItemRule = {
  validator: (_rule, value, callback) => {
    const text = typeof value === 'string' ? value.trim() : ''
    if (!text) {
      callback()
      return
    }
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(text)) {
      callback(new Error('请填写合法的 UUID'))
      return
    }
    callback()
  },
  trigger: ['blur', 'change']
}

const calendarVersionRule: FormItemRule = {
  validator: (_rule, value, callback) => {
    if (!Number.isInteger(value) || value < 1) {
      callback(new Error('日历版本需为不小于 1 的整数'))
      return
    }
    callback()
  },
  trigger: ['blur', 'change']
}

const rules: FormRules<CalendarFormModel> = {
  name: [required('请输入日历名称'), maxLength(256)],
  calendarVersion: [required('请输入日历版本'), calendarVersionRule],
  type: [required('请选择日历类型')],
  effectiveFrom: [required('请选择生效日期'), isoDate('生效日期格式应为 yyyy-MM-dd')],
  effectiveTo: [isoDate('失效日期格式应为 yyyy-MM-dd')],
  resourceId: [optionalUuidRule],
  resourceGroupId: [optionalUuidRule]
}

function createEmptyForm(): CalendarFormModel {
  return {
    name: '',
    calendarVersion: 1,
    type: 'FACTORY',
    resourceId: '',
    resourceGroupId: '',
    effectiveFrom: today(),
    effectiveTo: '',
    shifts: [{ startTime: '08:00', endTime: '17:00', daysOfWeek: [1, 2, 3, 4, 5] }],
    holidays: []
  }
}

function cloneShifts(shifts: Shift[]): Shift[] {
  return shifts.map((shift) => ({
    startTime: shift.startTime,
    endTime: shift.endTime,
    daysOfWeek: [...shift.daysOfWeek]
  }))
}

function cloneHolidays(holidays?: Holiday[]): Holiday[] {
  return (holidays ?? []).map((holiday) => ({ ...holiday }))
}

function fillForm(calendar: Calendar | null | undefined) {
  Object.assign(form, createEmptyForm())
  if (!calendar) {
    return
  }
  if (props.copyMode) {
    Object.assign(form, {
      name: calendar.name,
      calendarVersion: nextCalendarVersion(calendar.calendarVersion),
      type: calendar.type,
      resourceId: calendar.resourceId ?? '',
      resourceGroupId: calendar.resourceGroupId ?? '',
      effectiveFrom: today(),
      effectiveTo: '',
      shifts: cloneShifts(calendar.shifts),
      holidays: cloneHolidays(calendar.holidays)
    })
    return
  }
  Object.assign(form, {
    name: calendar.name,
    calendarVersion: calendar.calendarVersion,
    type: calendar.type,
    resourceId: calendar.resourceId ?? '',
    resourceGroupId: calendar.resourceGroupId ?? '',
    effectiveFrom: calendar.effectiveFrom,
    effectiveTo: calendar.effectiveTo ?? '',
    shifts: cloneShifts(calendar.shifts),
    holidays: cloneHolidays(calendar.holidays)
  })
}

function addShift() {
  form.shifts.push({ startTime: '08:00', endTime: '17:00', daysOfWeek: [1, 2, 3, 4, 5] })
}

function removeShift(index: number) {
  form.shifts.splice(index, 1)
}

function addHoliday() {
  form.holidays.push({ date: today(), name: '', kind: 'LEGAL' })
}

function removeHoliday(index: number) {
  form.holidays.splice(index, 1)
}

function isOvernight(shift: Shift): boolean {
  return shift.endTime <= shift.startTime
}

function syncJsonFromForm() {
  jsonText.value = JSON.stringify({ shifts: form.shifts, holidays: form.holidays }, null, 2)
  jsonError.value = ''
}

function validateShiftStructure(shifts: Shift[]): string | null {
  if (shifts.length === 0) {
    return '至少需要保留一个班次'
  }
  for (const shift of shifts) {
    if (!shift.startTime || !shift.endTime) {
      return '每个班次都需要填写起止时间'
    }
    if (shift.startTime === shift.endTime) {
      return `班次 ${shift.startTime} 的起止时间不能相同`
    }
    if (!Array.isArray(shift.daysOfWeek) || shift.daysOfWeek.length === 0) {
      return `班次 ${shift.startTime}-${shift.endTime} 至少选择一个工作日`
    }
    if (shift.daysOfWeek.some((day) => day < 1 || day > 7)) {
      return '工作日取值必须为 1-7（周一至周日）'
    }
  }
  return null
}

function validateHolidayStructure(holidays: Holiday[]): string | null {
  for (const holiday of holidays) {
    if (!holiday.date || !/^\d{4}-\d{2}-\d{2}$/.test(holiday.date)) {
      return '例外日期的格式应为 yyyy-MM-dd'
    }
    if (!holiday.name || !holiday.name.trim()) {
      return '例外日期需要填写名称'
    }
    if (!['LEGAL', 'COMPANY', 'MAKEUP'].includes(holiday.kind)) {
      return '例外类型只能为 LEGAL / COMPANY / MAKEUP'
    }
  }
  return null
}

/** 解析 JSON 文本并回填可视化表单；返回是否成功。 */
function applyJson(): boolean {
  try {
    const parsed = JSON.parse(jsonText.value || '{}') as {
      shifts?: Shift[]
      holidays?: Holiday[]
    }
    if (!Array.isArray(parsed.shifts)) {
      jsonError.value = 'JSON 必须包含 shifts 数组'
      return false
    }
    const shifts = parsed.shifts.map((shift) => ({
      startTime: String(shift.startTime ?? ''),
      endTime: String(shift.endTime ?? ''),
      daysOfWeek: Array.isArray(shift.daysOfWeek) ? shift.daysOfWeek.map(Number) : []
    }))
    const shiftError = validateShiftStructure(shifts)
    if (shiftError) {
      jsonError.value = shiftError
      return false
    }
    const holidays = Array.isArray(parsed.holidays)
      ? parsed.holidays.map((holiday) => ({
          date: String(holiday.date ?? ''),
          name: String(holiday.name ?? ''),
          kind: holiday.kind
        }))
      : []
    const holidayError = validateHolidayStructure(holidays as Holiday[])
    if (holidayError) {
      jsonError.value = holidayError
      return false
    }
    form.shifts = shifts
    form.holidays = holidays as Holiday[]
    jsonError.value = ''
    ElMessage.success('JSON 已校验并应用到可视化编辑')
    return true
  } catch {
    jsonError.value = 'JSON 不是合法结构，请检查括号与引号'
    return false
  }
}

function close() {
  emit('update:modelValue', false)
}

async function submit() {
  if (!formRef.value) {
    return
  }
  if (isHistoricalSource.value && !props.copyMode) {
    ElMessage.warning('历史日历只读，请使用「复制为新版本」')
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (activeTab.value === 'json' && !applyJson()) {
    return
  }
  const shiftError = validateShiftStructure(form.shifts)
  if (shiftError) {
    ElMessage.warning(shiftError)
    return
  }
  const holidayError = validateHolidayStructure(form.holidays)
  if (holidayError) {
    ElMessage.warning(holidayError)
    return
  }
  if (form.effectiveTo && form.effectiveTo < form.effectiveFrom) {
    ElMessage.warning('失效日期不能早于生效日期')
    return
  }
  submitting.value = true
  try {
    const payload: CalendarCreateRequest = {
      name: form.name.trim(),
      calendarVersion: form.calendarVersion,
      type: form.type,
      resourceId: isFactoryCalendar.value ? null : form.resourceId || null,
      resourceGroupId: form.resourceGroupId.trim() || null,
      effectiveFrom: form.effectiveFrom,
      effectiveTo: form.effectiveTo || null,
      shifts: form.shifts,
      holidays: form.holidays
    }
    if (props.calendar && !props.copyMode) {
      const updated = await updateCalendar(
        props.calendar.id,
        { ...payload, version: props.calendar.version },
        createIdempotencyKey('calendar-update')
      )
      ElMessage.success('日历已更新')
      emit('saved', updated)
    } else {
      const created = await createCalendar(payload, createIdempotencyKey('calendar-create'))
      ElMessage.success('日历已创建')
      emit('saved', created)
    }
    close()
  } catch {
    // 统一拦截器已提示（历史日历 403、版本冲突 409、结构错误 422）
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      fillForm(props.calendar)
      activeTab.value = 'visual'
      syncJsonFromForm()
      formRef.value?.clearValidate()
    }
  }
)

watch(
  () => form.type,
  (type) => {
    if (type === 'FACTORY') {
      form.resourceId = ''
    }
  }
)

watch(activeTab, (tab) => {
  if (tab === 'json') {
    syncJsonFromForm()
  }
})
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="960px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      v-if="isHistoricalSource && !copyMode"
      class="form-alert"
      type="error"
      :closable="false"
      show-icon
      :title="`该日历为${lifecycleLabel('HISTORICAL')}，PRD 要求历史日历不可修改；请使用「复制为新版本」。`"
    />
    <el-alert
      v-else-if="isEffectiveSource && !copyMode"
      class="form-alert"
      type="warning"
      :closable="false"
      show-icon
      title="该日历正在生效：修改会影响后续排程可用工时，建议保存前确认，或另建新版本覆盖。"
    />
    <el-alert
      v-else-if="copyMode"
      class="form-alert"
      type="info"
      :closable="false"
      show-icon
      title="已按历史日历内容生成新版本草稿：请确认新版本号与生效日期后再保存。"
    />

    <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="日历名称" prop="name">
            <el-input v-model="form.name" maxlength="256" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="日历版本" prop="calendarVersion">
            <el-input-number
              v-model="form.calendarVersion"
              :min="1"
              :step="1"
              step-strictly
              style="width: 160px"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="日历类型" prop="type">
            <el-select v-model="form.type" style="width: 100%">
              <el-option
                v-for="item in calendarTypeOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item v-if="!isFactoryCalendar" label="绑定资源" prop="resourceId">
            <el-input v-model="form.resourceId" placeholder="资源 UUID（RESOURCE 类型可填）" />
            <div class="form-hint">
              calendar.resourceId 为资源 UUID；资源列表接口未暴露 UUID，属契约缺口。
            </div>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="资源组" prop="resourceGroupId">
            <el-input v-model="form.resourceGroupId" placeholder="资源组 UUID（可空）" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="生效日期" prop="effectiveFrom">
            <el-date-picker
              v-model="form.effectiveFrom"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="yyyy-MM-dd"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="失效日期" prop="effectiveTo">
            <el-date-picker
              v-model="form.effectiveTo"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="可空（长期有效）"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="可视化编辑" name="visual">
          <div class="section-title">班次结构</div>
          <el-table :data="form.shifts" border size="small">
            <el-table-column label="开始时间" width="150">
              <template #default="{ row }">
                <el-time-select
                  v-model="row.startTime"
                  v-bind="timeSelectProps"
                  placeholder="开始"
                  style="width: 100%"
                />
              </template>
            </el-table-column>
            <el-table-column label="结束时间" width="150">
              <template #default="{ row }">
                <el-time-select
                  v-model="row.endTime"
                  v-bind="timeSelectProps"
                  placeholder="结束"
                  style="width: 100%"
                />
              </template>
            </el-table-column>
            <el-table-column label="工作日" min-width="260">
              <template #default="{ row }">
                <el-select v-model="row.daysOfWeek" multiple collapse-tags style="width: 100%">
                  <el-option
                    v-for="day in dayOptions"
                    :key="day.value"
                    :label="day.label"
                    :value="day.value"
                  />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="跨零点" width="90">
              <template #default="{ row }">
                <el-tag v-if="isOvernight(row)" type="warning" size="small">跨零点</el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeShift($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button class="add-row" plain @click="addShift">添加班次</el-button>

          <div class="section-title">时段预览（24 小时 × 周一至周日）</div>
          <ShiftTimeline :shifts="form.shifts" />

          <div class="section-title">例外日期 / 节假日</div>
          <el-table :data="form.holidays" border size="small" empty-text="暂无例外日期">
            <el-table-column label="日期" width="180">
              <template #default="{ row }">
                <el-date-picker
                  v-model="row.date"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="yyyy-MM-dd"
                  style="width: 100%"
                />
              </template>
            </el-table-column>
            <el-table-column label="名称" min-width="200">
              <template #default="{ row }">
                <el-input v-model="row.name" size="small" placeholder="如 国庆节 / 调休上班" />
              </template>
            </el-table-column>
            <el-table-column label="类型" width="160">
              <template #default="{ row }">
                <el-select v-model="row.kind" size="small" style="width: 100%">
                  <el-option
                    v-for="item in holidayKindOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeHoliday($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button class="add-row" plain @click="addHoliday">添加例外日期</el-button>

          <el-alert
            class="form-alert"
            type="info"
            :closable="false"
            show-icon
            title="班次结构按 schema.md 5.6：shifts[{startTime,endTime,daysOfWeek[1-7]}]；holidays[{date,name,kind}]。结束时间早于开始时间表示跨零点。"
          />
        </el-tab-pane>

        <el-tab-pane label="JSON 结构" name="json">
          <el-input
            v-model="jsonText"
            type="textarea"
            :rows="16"
            spellcheck="false"
            placeholder="保持 { shifts: [...], holidays: [...] } 结构"
            @input="jsonError = ''"
          />
          <el-alert
            v-if="jsonError"
            class="form-alert"
            type="error"
            :closable="false"
            show-icon
            :title="jsonError"
          />
          <div class="json-actions">
            <el-button type="primary" plain @click="applyJson">校验并应用到可视化</el-button>
            <el-button @click="syncJsonFromForm">从可视化重新生成</el-button>
          </div>
          <div class="form-hint">
            JSON 与可视化是同一份数据：保存时若停留在 JSON 页签，会先校验后再提交。
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-form>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-alert {
  margin-bottom: 12px;
}

.section-title {
  margin: 12px 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.add-row {
  margin-top: 10px;
}

.json-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.form-hint {
  margin-top: 6px;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.6;
}
</style>
