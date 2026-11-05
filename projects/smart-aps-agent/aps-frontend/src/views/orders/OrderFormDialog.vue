<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'

import { createIdempotencyKey } from '@/api/http'
import { createOrder, updateOrder } from '@/api/orders'
import MaterialSelect from '@/components/master-data/MaterialSelect.vue'
import { getEnumOptions } from '@/constants/enums'
import { businessCode, isoDate, maxLength, positiveNumber, required } from '@/utils/form-rules'
import type { Order, OrderPriority } from '@/types/orders'

interface OrderFormModel {
  orderNo: string
  materialCode: string
  qty: number | undefined
  dueDate: string
  priority: OrderPriority
  customerId: string
  remark: string
}

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    source?: Order | null
  }>(),
  {
    source: null
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [order: Order]
}>()

const priorityOptions = getEnumOptions('orderPriority')
const formRef = ref<FormInstance>()
const submitting = ref(false)

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const editing = computed(() => Boolean(props.source))

const form = reactive<OrderFormModel>(createEmptyForm())

const rules: FormRules<OrderFormModel> = {
  orderNo: [required('请输入订单号'), businessCode(), maxLength(64)],
  materialCode: [required('请选择产品物料')],
  qty: [required('请输入数量'), positiveNumber('数量必须大于 0')],
  dueDate: [required('请选择交期'), isoDate('交期格式应为 yyyy-MM-dd')],
  customerId: [maxLength(36, '客户 ID 不能超过 36 个字符')]
}

function createEmptyForm(): OrderFormModel {
  return {
    orderNo: '',
    materialCode: '',
    qty: undefined,
    dueDate: '',
    priority: 'NORMAL',
    customerId: '',
    remark: ''
  }
}

function fillForm(order: Order | null) {
  Object.assign(form, createEmptyForm())
  if (!order) {
    return
  }
  Object.assign(form, {
    orderNo: order.orderNo,
    materialCode: order.materialCode,
    qty: order.qty,
    dueDate: order.dueDate,
    priority: order.priority,
    customerId: order.customerId ?? '',
    remark: order.remark ?? ''
  })
}

watch(
  () => props.modelValue,
  (opened) => {
    if (!opened) {
      return
    }
    fillForm(props.source)
    formRef.value?.clearValidate()
  }
)

watch(
  () => props.source,
  (order) => {
    if (props.modelValue) {
      fillForm(order)
    }
  }
)

async function submit() {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid || form.qty === undefined) {
    return
  }
  const common = {
    materialCode: form.materialCode,
    qty: form.qty,
    dueDate: form.dueDate,
    priority: form.priority,
    customerId: form.customerId.trim() || null,
    routingId: props.source?.routingId ?? null
  }
  const remarkText = form.remark.trim()
  submitting.value = true
  try {
    const key = createIdempotencyKey(editing.value ? 'order-update' : 'order-create')
    const saved = editing.value
      ? await updateOrder(
          props.source!.orderNo,
          { ...common, remark: remarkText || null, version: props.source!.version },
          key
        )
      : await createOrder(
          {
            orderNo: form.orderNo.trim(),
            source: 'MANUAL',
            ...common,
            remark: remarkText || undefined
          },
          key
        )
    ElMessage.success(editing.value ? '订单已更新' : '订单已创建')
    emit('saved', saved)
    visible.value = false
  } catch {
    // 统一拦截器已提示（409 重复单号 / 422 业务校验），保留表单便于修正
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="editing ? `编辑订单 ${source?.orderNo}` : '新建订单'"
    width="560px"
    destroy-on-close
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
      <el-form-item label="订单号" prop="orderNo">
        <el-input
          v-model="form.orderNo"
          :disabled="editing"
          maxlength="64"
          placeholder="租户内唯一，如 SO-20261104-0010"
        />
      </el-form-item>
      <el-form-item label="物料" prop="materialCode">
        <MaterialSelect v-model="form.materialCode" />
      </el-form-item>
      <el-form-item label="数量" prop="qty">
        <el-input-number v-model="form.qty" :min="0.01" :precision="2" style="width: 100%" />
      </el-form-item>
      <el-form-item label="交期" prop="dueDate">
        <el-date-picker
          v-model="form.dueDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择交期"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="优先级" prop="priority">
        <el-select v-model="form.priority" style="width: 100%">
          <el-option
            v-for="item in priorityOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="客户" prop="customerId">
        <el-input v-model="form.customerId" clearable placeholder="客户 ID（可空）" />
      </el-form-item>
      <el-form-item label="备注" prop="remark">
        <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" show-word-limit />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>
