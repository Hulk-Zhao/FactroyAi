<script setup lang="ts">
/**
 * 新建工艺路线版本对话框。
 * 支持空白新建，或从现有版本复制节点/边（sourceRoutingId）生成 DRAFT。
 */
import { reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { createRouting, toRoutingEdgePayload, toRoutingNodePayload } from '@/api/routings'
import { createIdempotencyKey } from '@/api/http'
import MaterialSelect from '@/components/master-data/MaterialSelect.vue'
import { isoDate, maxLength, required } from '@/utils/form-rules'
import type { Routing } from '@/types/routings'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    source?: Routing | null
    defaultProductCode?: string
  }>(),
  {
    source: null,
    defaultProductCode: ''
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [routing: Routing]
}>()

const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  productCode: '',
  routingVersion: 'V1.0',
  effectiveDate: new Date().toISOString().slice(0, 10),
  expiryDate: ''
})

const rules: FormRules = {
  productCode: [required('请选择产品物料')],
  routingVersion: [required('请填写版本号'), maxLength(32)],
  effectiveDate: [required('请选择生效日期'), isoDate()],
  expiryDate: [isoDate()]
}

function suggestedVersion(version?: string): string {
  if (!version) {
    return 'V1.0'
  }
  const match = /^V(\d+)\.(\d+)$/.exec(version)
  if (!match) {
    return `${version}-COPY`
  }
  return `V${match[1]}.${Number(match[2]) + 1}`
}

function reset() {
  form.productCode = props.source?.productCode ?? props.defaultProductCode
  form.routingVersion = suggestedVersion(props.source?.routingVersion)
  form.effectiveDate = new Date().toISOString().slice(0, 10)
  form.expiryDate = ''
  formRef.value?.clearValidate()
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      reset()
    }
  }
)

function close() {
  emit('update:modelValue', false)
}

async function submit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  if (!props.source || props.source.nodes.length === 0) {
    ElMessage.error('请选择包含工序节点的来源版本')
    return
  }
  submitting.value = true
  try {
    const routing = await createRouting(
      {
        productCode: form.productCode,
        routingVersion: form.routingVersion.trim(),
        effectiveDate: form.effectiveDate,
        expiryDate: form.expiryDate || null,
        nodes: props.source.nodes.map(toRoutingNodePayload),
        edges: props.source.edges.map(toRoutingEdgePayload)
      },
      createIdempotencyKey('routing')
    )
    ElMessage.success(`已创建 ${routing.productCode} / ${routing.routingVersion}`)
    emit('saved', routing)
    close()
  } catch {
    // 统一拦截器已提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="source ? '新建工艺路线版本' : '新建工艺路线'"
    width="520px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      v-if="source"
      type="info"
      :closable="false"
      show-icon
      class="copy-tip"
      title="将从所选版本复制工序节点与依赖边，生成新的草稿版本。"
    />

    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
      <el-form-item label="产品物料" prop="productCode">
        <MaterialSelect v-model="form.productCode" :disabled="Boolean(source)" />
      </el-form-item>
      <el-form-item label="版本号" prop="routingVersion">
        <el-input v-model="form.routingVersion" maxlength="32" placeholder="如 V1.1" />
      </el-form-item>
      <el-form-item label="生效日期" prop="effectiveDate">
        <el-date-picker
          v-model="form.effectiveDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择生效日期"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="失效日期" prop="expiryDate">
        <el-date-picker
          v-model="form.expiryDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="可留空"
          clearable
          style="width: 100%"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">创建草稿</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.copy-tip {
  margin-bottom: 12px;
}
</style>
