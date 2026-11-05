<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'

import { createBom } from '@/api/boms'
import { createIdempotencyKey } from '@/api/http'
import MaterialSelect from '@/components/master-data/MaterialSelect.vue'
import { isoDate, required } from '@/utils/form-rules'
import type { Bom, BomCreateRequest, BomItem } from '@/types/boms'

interface BomItemForm {
  materialCode: string
  qtyPerUnit: number
  scrapRate: number
  position: string
  substitutesText: string
  isPhantom: boolean
}

interface BomFormModel {
  parentMaterialCode: string
  bomVersion: string
  effectiveDate: string
  expiryDate: string
  isPanel: boolean
  panelSize: number | undefined
  items: BomItemForm[]
}

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    parentCode?: string | null
    source?: Bom | null
  }>(),
  {
    parentCode: null,
    source: null
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [bom: Bom]
}>()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive<BomFormModel>(createEmptyForm())

const parentLocked = computed(() => Boolean(props.parentCode || props.source))

const rules: FormRules<BomFormModel> = {
  parentMaterialCode: [required('请选择父件物料')],
  bomVersion: [required('请输入 BOM 版本')],
  effectiveDate: [required('请选择生效日期'), isoDate('生效日期格式应为 yyyy-MM-dd')],
  expiryDate: [isoDate('失效日期格式应为 yyyy-MM-dd')]
}

function emptyItem(): BomItemForm {
  return {
    materialCode: '',
    qtyPerUnit: 1,
    scrapRate: 0,
    position: '',
    substitutesText: '',
    isPhantom: false
  }
}

function createEmptyForm(): BomFormModel {
  return {
    parentMaterialCode: '',
    bomVersion: '',
    effectiveDate: new Date().toISOString().slice(0, 10),
    expiryDate: '',
    isPanel: false,
    panelSize: undefined,
    items: [emptyItem()]
  }
}

function nextVersion(version?: string): string {
  const matched = version?.match(/^V(\d+)\.(\d+)$/i)
  if (!matched) {
    return ''
  }
  return `V${Number(matched[1]) + 1}.${matched[2]}`
}

function fillForm(parentCode: string | null | undefined, source: Bom | null | undefined) {
  Object.assign(form, createEmptyForm())
  form.parentMaterialCode = parentCode || source?.parentMaterialCode || ''
  form.bomVersion = nextVersion(source?.bomVersion)
  if (source) {
    form.effectiveDate = source.effectiveDate || form.effectiveDate
    form.expiryDate = source.expiryDate ?? ''
    form.isPanel = source.isPanel ?? false
    form.panelSize = source.panelSize ?? undefined
    form.items = source.items.map((item) => ({
      materialCode: item.materialCode,
      qtyPerUnit: item.qtyPerUnit,
      scrapRate: item.scrapRate ?? 0,
      position: item.position ?? '',
      substitutesText: item.substitutes?.join(',') ?? '',
      isPhantom: item.isPhantom ?? false
    }))
    if (form.items.length === 0) {
      form.items = [emptyItem()]
    }
  }
}

function addItem() {
  form.items.push(emptyItem())
}

function removeItem(index: number) {
  form.items.splice(index, 1)
}

function parseSubstitutes(text: string): string[] {
  return text
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

function validateItems(): string | null {
  if (form.items.length === 0) {
    return '至少添加一行组件明细'
  }
  const codes = new Set<string>()
  for (const item of form.items) {
    if (!item.materialCode) {
      return '请为每行明细选择组件物料'
    }
    if (!(item.qtyPerUnit > 0)) {
      return `组件 ${item.materialCode} 的单位用量必须大于 0`
    }
    if (item.scrapRate < 0 || item.scrapRate > 1) {
      return `组件 ${item.materialCode} 的损耗率需在 0-1 之间`
    }
    if (codes.has(item.materialCode)) {
      return `组件 ${item.materialCode} 重复，请合并用量`
    }
    codes.add(item.materialCode)
  }
  if (form.isPanel && (!form.panelSize || form.panelSize <= 0)) {
    return '拼板数量必须大于 0'
  }
  return null
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
  const itemError = validateItems()
  if (itemError) {
    ElMessage.warning(itemError)
    return
  }
  submitting.value = true
  try {
    const items: BomItem[] = form.items.map((item) => ({
      materialCode: item.materialCode,
      qtyPerUnit: item.qtyPerUnit,
      scrapRate: item.scrapRate,
      position: item.position.trim() || undefined,
      substitutes: parseSubstitutes(item.substitutesText),
      isPhantom: item.isPhantom
    }))
    const payload: BomCreateRequest = {
      parentMaterialCode: form.parentMaterialCode,
      bomVersion: form.bomVersion.trim(),
      effectiveDate: form.effectiveDate,
      expiryDate: form.expiryDate || null,
      isPanel: form.isPanel,
      panelSize: form.isPanel ? form.panelSize : null,
      items
    }
    const created = await createBom(payload, createIdempotencyKey('bom-create'))
    ElMessage.success(`BOM ${created.bomVersion} 已创建（草稿）`)
    emit('saved', created)
    close()
  } catch {
    // 统一拦截器已提示（409 同父件同版本、422 物料引用等）
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      fillForm(props.parentCode, props.source)
      formRef.value?.clearValidate()
    }
  }
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="新建 BOM 版本"
    width="960px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="父件物料" prop="parentMaterialCode">
            <MaterialSelect
              v-model="form.parentMaterialCode"
              :disabled="parentLocked"
              placeholder="选择父件物料"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="BOM 版本" prop="bomVersion">
            <el-input v-model="form.bomVersion" placeholder="如 V1.0 / V2.0" maxlength="32" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="生效日期" prop="effectiveDate">
            <el-date-picker
              v-model="form.effectiveDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="yyyy-MM-dd"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="失效日期" prop="expiryDate">
            <el-date-picker
              v-model="form.expiryDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="可空"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="4">
          <el-form-item label="拼板" prop="isPanel">
            <el-switch v-model="form.isPanel" />
          </el-form-item>
        </el-col>
        <el-col :span="4">
          <el-form-item label="拼数" prop="panelSize">
            <el-input-number
              v-model="form.panelSize"
              :min="1"
              :precision="0"
              :disabled="!form.isPanel"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <div class="section-title">组件明细</div>
      <el-table :data="form.items" border size="small">
        <el-table-column label="组件物料" min-width="220">
          <template #default="{ row }">
            <MaterialSelect v-model="row.materialCode" placeholder="搜索组件物料" />
          </template>
        </el-table-column>
        <el-table-column label="单位用量" width="110">
          <template #default="{ row }">
            <el-input-number
              v-model="row.qtyPerUnit"
              :min="0.0001"
              :controls="false"
              size="small"
              style="width: 100%"
            />
          </template>
        </el-table-column>
        <el-table-column label="损耗率" width="110">
          <template #default="{ row }">
            <el-input-number
              v-model="row.scrapRate"
              :min="0"
              :max="1"
              :step="0.01"
              :precision="3"
              :controls="false"
              size="small"
              style="width: 100%"
            />
          </template>
        </el-table-column>
        <el-table-column label="位置" width="100">
          <template #default="{ row }">
            <el-input v-model="row.position" size="small" placeholder="如 P1" />
          </template>
        </el-table-column>
        <el-table-column label="替代料" min-width="150">
          <template #default="{ row }">
            <el-input v-model="row.substitutesText" size="small" placeholder="逗号分隔" />
          </template>
        </el-table-column>
        <el-table-column label="虚拟件" width="80">
          <template #default="{ row }">
            <el-switch v-model="row.isPhantom" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeItem($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button class="add-item" plain @click="addItem">添加组件</el-button>

      <el-alert
        class="form-alert"
        type="warning"
        :closable="false"
        show-icon
        title="同一父件仅允许一个 ACTIVE 版本（C8）；新版本默认保存为 DRAFT，需在列表手动激活。"
      />
    </el-form>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存为草稿</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.section-title {
  margin: 8px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.add-item {
  margin-top: 12px;
}

.form-alert {
  margin-top: 16px;
}
</style>
