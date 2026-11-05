<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormItemRule, FormRules } from 'element-plus'

import { createIdempotencyKey } from '@/api/http'
import { createMaterial, updateMaterial } from '@/api/materials'
import { getEnumOptions } from '@/constants/enums'
import { businessCode, maxLength, nonNegativeNumber, required } from '@/utils/form-rules'
import type { Material, MaterialCreateRequest, MaterialType } from '@/types/materials'

interface MaterialFormModel {
  code: string
  name: string
  type: MaterialType
  spec: string
  unit: string
  categoryId: string
  safetyStock: number | undefined
  moq: number | undefined
  leadTimeDays: number | undefined
  extAttrsText: string
}

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    material?: Material | null
  }>(),
  {
    material: null
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [material: Material]
}>()

const materialTypeOptions = getEnumOptions('materialType')
const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive<MaterialFormModel>(createEmptyForm())

function createEmptyForm(): MaterialFormModel {
  return {
    code: '',
    name: '',
    type: 'RAW',
    spec: '',
    unit: '',
    categoryId: '',
    safetyStock: undefined,
    moq: undefined,
    leadTimeDays: undefined,
    extAttrsText: ''
  }
}

function validateExtAttrs(_rule: unknown, value: string, callback: (error?: Error) => void) {
  if (!value || !value.trim()) {
    callback()
    return
  }
  try {
    const parsed: unknown = JSON.parse(value)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      callback(new Error('ext_attrs 必须为 JSON 对象'))
      return
    }
    callback()
  } catch {
    callback(new Error('ext_attrs 不是合法 JSON，例如 {"brand":"HENGCE"}'))
  }
}

const extAttrsRule: FormItemRule = {
  validator: validateExtAttrs,
  trigger: ['blur', 'change']
}

const rules: FormRules<MaterialFormModel> = {
  code: [required('请输入物料编码'), businessCode(), maxLength(64)],
  name: [required('请输入物料名称'), maxLength(128)],
  type: [required('请选择物料类型')],
  unit: [required('请输入计量单位'), maxLength(16)],
  safetyStock: [nonNegativeNumber('安全库存不能小于 0')],
  moq: [nonNegativeNumber('最小起订量不能小于 0')],
  leadTimeDays: [nonNegativeNumber('采购提前期不能小于 0')],
  extAttrsText: [extAttrsRule]
}

function fillForm(material: Material | null) {
  Object.assign(form, createEmptyForm())
  if (!material) {
    return
  }
  Object.assign(form, {
    code: material.code,
    name: material.name,
    type: material.type,
    spec: material.spec ?? '',
    unit: material.unit,
    categoryId: material.categoryId ?? '',
    safetyStock: material.safetyStock,
    moq: material.moq,
    leadTimeDays: material.leadTimeDays,
    extAttrsText: material.extAttrs ? JSON.stringify(material.extAttrs, null, 2) : ''
  })
}

function parseExtAttrs(): Record<string, unknown> {
  if (!form.extAttrsText.trim()) {
    return {}
  }
  return JSON.parse(form.extAttrsText) as Record<string, unknown>
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
  let extAttrs: Record<string, unknown>
  try {
    extAttrs = parseExtAttrs()
  } catch {
    ElMessage.warning('扩展属性 ext_attrs 需为合法 JSON 对象')
    return
  }
  submitting.value = true
  try {
    const payload: MaterialCreateRequest = {
      code: form.code.trim(),
      name: form.name.trim(),
      type: form.type,
      unit: form.unit.trim(),
      spec: form.spec.trim() || undefined,
      categoryId: form.categoryId.trim() || null,
      safetyStock: form.safetyStock,
      moq: form.moq,
      leadTimeDays: form.leadTimeDays,
      extAttrs
    }
    if (props.material) {
      const updated = await updateMaterial(
        props.material.code,
        { ...payload, version: props.material.version },
        createIdempotencyKey('material-update')
      )
      ElMessage.success('物料已更新')
      emit('saved', updated)
    } else {
      const created = await createMaterial(payload, createIdempotencyKey('material-create'))
      ElMessage.success('物料已创建')
      emit('saved', created)
    }
    close()
  } catch {
    // 错误提示由 http 拦截器统一处理，此处仅保持弹窗打开
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      fillForm(props.material)
      formRef.value?.clearValidate()
    }
  }
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="material ? '编辑物料' : '新建物料'"
    width="720px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="物料编码" prop="code">
            <el-input v-model="form.code" :disabled="Boolean(material)" maxlength="64" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="物料名称" prop="name">
            <el-input v-model="form.name" maxlength="128" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="物料类型" prop="type">
            <el-select v-model="form.type" style="width: 100%">
              <el-option
                v-for="item in materialTypeOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="计量单位" prop="unit">
            <el-input v-model="form.unit" maxlength="16" placeholder="如 个 / 套 / 米" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="规格" prop="spec">
            <el-input v-model="form.spec" placeholder="如 48V 15Ah 16S1P" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="分类" prop="categoryId">
            <el-input v-model="form.categoryId" placeholder="物料分类 UUID（可空）" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="安全库存" prop="safetyStock">
            <el-input-number v-model="form.safetyStock" :min="0" :controls="false" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="最小起订量" prop="moq">
            <el-input-number v-model="form.moq" :min="0" :controls="false" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="提前期(天)" prop="leadTimeDays">
            <el-input-number v-model="form.leadTimeDays" :min="0" :precision="0" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="扩展属性" prop="extAttrsText">
        <el-input
          v-model="form.extAttrsText"
          type="textarea"
          :rows="5"
          placeholder='JSON 对象，例如 {"brand":"HENGCE","tags":["rohs"]}'
        />
        <div class="form-hint">留空表示无扩展属性；保存前会校验 JSON 结构。</div>
      </el-form-item>

      <el-alert
        v-if="material"
        type="info"
        :closable="false"
        show-icon
        title="编辑时需携带 version 做乐观锁校验；编码创建后不可修改。"
      />
    </el-form>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-hint {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.6;
}
</style>
