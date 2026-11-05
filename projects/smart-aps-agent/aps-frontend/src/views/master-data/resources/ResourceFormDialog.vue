<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormItemRule, FormRules } from 'element-plus'

import { listCalendars } from '@/api/calendars'
import { createIdempotencyKey } from '@/api/http'
import { createResource, updateResource } from '@/api/resources'
import { getEnumOptions, getEnumLabel } from '@/constants/enums'
import { businessCode, maxLength, nonNegativeNumber, required } from '@/utils/form-rules'
import type { Calendar } from '@/types/calendars'
import type { CapacityMode, Resource, ResourceCreateRequest, ResourceType } from '@/types/resources'

import { computeCapacity, formatSeconds } from './capacity'

interface ResourceFormModel {
  code: string
  name: string
  type: ResourceType
  groupId: string
  capacityMode: CapacityMode
  capacityValue: number | undefined
  batchCapacity: number | undefined
  efficiency: number
  calendarId: string
  setupSeconds: number | undefined
  teardownSeconds: number | undefined
  trialStandardSeconds: number | undefined
  trialQty: number
}

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    resource?: Resource | null
  }>(),
  {
    resource: null
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [resource: Resource]
}>()

const resourceTypeOptions = getEnumOptions('resourceType')
const capacityModeOptions = getEnumOptions('capacityMode')

const formRef = ref<FormInstance>()
const submitting = ref(false)
const calendars = ref<Calendar[]>([])
const calendarLoading = ref(false)
const form = reactive<ResourceFormModel>(createEmptyForm())

const isBatchMode = computed(() => form.capacityMode === 'BATCH_SECONDS')
const isSecondsMode = computed(() => form.capacityMode === 'SECONDS_PER_UNIT')

const capacityFieldLabel = computed(() => {
  if (form.capacityMode === 'UNIT_PER_HOUR') {
    return '产能（件/小时）'
  }
  if (form.capacityMode === 'BATCH_SECONDS') {
    return '每批工时（秒）'
  }
  return '产能值（该模式不参与计算）'
})

const capacityModeHint = computed(() => {
  switch (form.capacityMode) {
    case 'UNIT_PER_HOUR':
      return '有效产能 C = capacity_value × efficiency，单件工时 = 60 / C 分钟。'
    case 'SECONDS_PER_UNIT':
      return '单件工时 = 工序 standard_seconds ÷ efficiency，capacity_value 不参与计算。'
    case 'BATCH_SECONDS':
      return '批次数 n = ceil(qty / batch_capacity)，每批工时 = capacity_value ÷ efficiency。'
    default:
      return ''
  }
})

const capacityPreview = computed(() =>
  computeCapacity({
    capacityMode: form.capacityMode,
    capacityValue: form.capacityValue,
    batchCapacity: form.batchCapacity,
    efficiency: form.efficiency,
    setupSeconds: form.setupSeconds,
    standardSeconds: form.trialStandardSeconds,
    qty: form.trialQty
  })
)

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

const capacityValueRule: FormItemRule = {
  validator: (_rule, value, callback) => {
    if (isSecondsMode.value) {
      callback()
      return
    }
    if (typeof value !== 'number' || Number.isNaN(value) || value <= 0) {
      callback(new Error('产能值必须大于 0'))
      return
    }
    callback()
  },
  trigger: ['blur', 'change']
}

const batchCapacityRule: FormItemRule = {
  validator: (_rule, value, callback) => {
    if (!isBatchMode.value) {
      callback()
      return
    }
    if (typeof value !== 'number' || Number.isNaN(value) || value <= 0) {
      callback(new Error('批次秒模式必须填写大于 0 的批容量'))
      return
    }
    callback()
  },
  trigger: ['blur', 'change']
}

const efficiencyRule: FormItemRule = {
  validator: (_rule, value, callback) => {
    if (typeof value !== 'number' || Number.isNaN(value)) {
      callback(new Error('效率系数必填'))
      return
    }
    if (value < 0.5 || value > 1.5) {
      callback(new Error('效率系数需在 0.5–1.5 之间（schema CHECK）'))
      return
    }
    callback()
  },
  trigger: ['blur', 'change']
}

const rules: FormRules<ResourceFormModel> = {
  code: [required('请输入资源编码'), businessCode(), maxLength(64)],
  name: [required('请输入资源名称'), maxLength(256)],
  type: [required('请选择资源类型')],
  capacityMode: [required('请选择产能模式')],
  capacityValue: [capacityValueRule],
  batchCapacity: [batchCapacityRule],
  efficiency: [efficiencyRule],
  groupId: [optionalUuidRule],
  setupSeconds: [nonNegativeNumber('换型工时不能小于 0')],
  teardownSeconds: [nonNegativeNumber('拆卸工时不能小于 0')],
  trialStandardSeconds: [nonNegativeNumber('标准工时不能小于 0')],
  trialQty: [nonNegativeNumber('示例数量不能小于 0')]
}

function createEmptyForm(): ResourceFormModel {
  return {
    code: '',
    name: '',
    type: 'PRIMARY',
    groupId: '',
    capacityMode: 'UNIT_PER_HOUR',
    capacityValue: undefined,
    batchCapacity: undefined,
    efficiency: 1,
    calendarId: '',
    setupSeconds: 0,
    teardownSeconds: 0,
    trialStandardSeconds: undefined,
    trialQty: 100
  }
}

function fillForm(resource: Resource | null) {
  Object.assign(form, createEmptyForm())
  if (!resource) {
    return
  }
  Object.assign(form, {
    code: resource.code,
    name: resource.name,
    type: resource.type,
    groupId: resource.groupId ?? '',
    capacityMode: resource.capacityMode,
    capacityValue: resource.capacityValue,
    batchCapacity: resource.batchCapacity ?? undefined,
    efficiency: resource.efficiency ?? 1,
    calendarId: resource.calendarId ?? '',
    setupSeconds: resource.setupSeconds ?? 0,
    teardownSeconds: resource.teardownSeconds ?? 0
  })
}

async function loadCalendars() {
  calendarLoading.value = true
  try {
    const result = await listCalendars({ page: 1, size: 200, sort: 'name,asc' })
    calendars.value = result.items
  } catch {
    // 统一拦截器已提示；日历下拉为空不影响其它字段编辑
  } finally {
    calendarLoading.value = false
  }
}

function close() {
  emit('update:modelValue', false)
}

async function submit() {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (!isSecondsMode.value && !capacityPreview.value.valid) {
    ElMessage.warning(capacityPreview.value.message || '产能配置无法换算，请检查输入')
    return
  }
  submitting.value = true
  try {
    const payload: ResourceCreateRequest = {
      code: form.code.trim(),
      name: form.name.trim(),
      type: form.type,
      groupId: form.groupId.trim() || null,
      capacityMode: form.capacityMode,
      capacityValue: isSecondsMode.value ? (form.capacityValue ?? 0) : Number(form.capacityValue),
      batchCapacity: isBatchMode.value ? (form.batchCapacity ?? null) : null,
      efficiency: form.efficiency,
      calendarId: form.calendarId || null,
      setupSeconds: form.setupSeconds ?? 0,
      teardownSeconds: form.teardownSeconds ?? 0
    }
    if (props.resource) {
      const updated = await updateResource(
        props.resource.code,
        { ...payload, version: props.resource.version },
        createIdempotencyKey('resource-update')
      )
      ElMessage.success('资源已更新')
      emit('saved', updated)
    } else {
      const created = await createResource(payload, createIdempotencyKey('resource-create'))
      ElMessage.success('资源已创建')
      emit('saved', created)
    }
    close()
  } catch {
    // 统一拦截器已提示（409 版本冲突、422 日历引用/产能校验）
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      fillForm(props.resource)
      void loadCalendars()
      formRef.value?.clearValidate()
    }
  }
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="resource ? '编辑资源' : '新建资源'"
    width="860px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="130px">
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="资源编码" prop="code">
            <el-input v-model="form.code" :disabled="Boolean(resource)" maxlength="64" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="资源名称" prop="name">
            <el-input v-model="form.name" maxlength="256" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="资源类型" prop="type">
            <el-select v-model="form.type" style="width: 100%">
              <el-option
                v-for="item in resourceTypeOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="资源组" prop="groupId">
            <el-input v-model="form.groupId" placeholder="资源组 UUID（可空）" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="产能模式" prop="capacityMode">
            <el-select v-model="form.capacityMode" style="width: 100%">
              <el-option
                v-for="item in capacityModeOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item :label="capacityFieldLabel" prop="capacityValue">
            <el-input-number
              v-model="form.capacityValue"
              :min="0"
              :controls="false"
              :disabled="isSecondsMode"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col v-if="isBatchMode" :span="12">
          <el-form-item label="批容量(件/批)" prop="batchCapacity">
            <el-input-number
              v-model="form.batchCapacity"
              :min="1"
              :precision="0"
              :controls="false"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="效率系数" prop="efficiency">
            <el-input-number
              v-model="form.efficiency"
              :min="0.5"
              :max="1.5"
              :step="0.01"
              :precision="2"
              :controls="false"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="工作日历" prop="calendarId">
            <el-select
              v-model="form.calendarId"
              clearable
              filterable
              :loading="calendarLoading"
              placeholder="选择绑定的工作日历"
              style="width: 100%"
            >
              <el-option
                v-for="calendar in calendars"
                :key="calendar.id"
                :label="`${calendar.name} ${calendar.calendarVersion}（${calendar.effectiveFrom} 起）`"
                :value="calendar.id"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="换型工时(秒)" prop="setupSeconds">
            <el-input-number
              v-model="form.setupSeconds"
              :min="0"
              :precision="0"
              :controls="false"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="拆卸工时(秒)" prop="teardownSeconds">
            <el-input-number
              v-model="form.teardownSeconds"
              :min="0"
              :precision="0"
              :controls="false"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-alert
        class="mode-hint"
        type="info"
        :closable="false"
        show-icon
        :title="`${getEnumLabel('capacityMode', form.capacityMode)}：${capacityModeHint}`"
      />

      <div class="section-title">产能换算试算（契约 C2，仅前端展示，不落库）</div>
      <el-row :gutter="16" class="trial-inputs">
        <el-col v-if="isSecondsMode" :span="8">
          <el-form-item label="工序标准工时" prop="trialStandardSeconds">
            <el-input-number
              v-model="form.trialStandardSeconds"
              :min="0"
              :controls="false"
              placeholder="秒/件"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="示例数量" prop="trialQty">
            <el-input-number
              v-model="form.trialQty"
              :min="0"
              :precision="0"
              :controls="false"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-alert
        v-if="!capacityPreview.valid"
        type="warning"
        :closable="false"
        show-icon
        :title="capacityPreview.message || '当前配置无法换算'"
      />
      <el-descriptions v-else :column="2" border size="small">
        <el-descriptions-item
          v-if="capacityPreview.effectiveCapacityPerHour !== null"
          label="有效产能"
        >
          {{ capacityPreview.effectiveCapacityPerHour.toFixed(2) }} 件/小时
        </el-descriptions-item>
        <el-descriptions-item label="单件工时">
          {{ formatSeconds(capacityPreview.perUnitSeconds) }}
        </el-descriptions-item>
        <el-descriptions-item v-if="capacityPreview.batchCount !== null" label="批次数">
          {{ capacityPreview.batchCount }} 批
        </el-descriptions-item>
        <el-descriptions-item label="加工时长 T_run">
          {{ formatSeconds(capacityPreview.runSeconds) }}
        </el-descriptions-item>
        <el-descriptions-item label="总时长 T_total">
          {{ formatSeconds(capacityPreview.totalSeconds) }}（含换型
          {{ form.setupSeconds ?? 0 }} 秒）
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        class="form-alert"
        type="info"
        :closable="false"
        show-icon
        title="C2 规则：T_run 与 T_total 向上取整到整秒；efficiency 空按 1.00；batch_capacity 空按 1（批次秒模式不可为空）。"
      />
    </el-form>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.mode-hint {
  margin-bottom: 12px;
}

.section-title {
  margin: 4px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.trial-inputs {
  margin-bottom: 4px;
}

.form-alert {
  margin-top: 16px;
}
</style>
