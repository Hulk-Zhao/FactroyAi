<script setup lang="ts">
/**
 * 物料选择器：远程搜索复用物料列表接口，供 BOM 父件/子件选择。
 * 搜索关键字同时传给 code/name（兼容性扩展参数），由后端或 Mock 做 OR 过滤。
 */
import { computed, onMounted, ref, watch } from 'vue'

import { getMaterial, listMaterials } from '@/api/materials'
import type { Material, MaterialType } from '@/types/materials'

const props = withDefaults(
  defineProps<{
    modelValue?: string | null
    type?: MaterialType
    placeholder?: string
    disabled?: boolean
    clearable?: boolean
  }>(),
  {
    modelValue: null,
    placeholder: '输入编码或名称搜索物料',
    disabled: false,
    clearable: true
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string | null]
  change: [material: Material | null]
}>()

const options = ref<Material[]>([])
const loading = ref(false)

const selectedCode = computed(() => props.modelValue ?? undefined)

function mergeOption(material: Material) {
  if (!options.value.some((item) => item.code === material.code)) {
    options.value = [material, ...options.value]
  }
}

async function search(keyword: string) {
  loading.value = true
  try {
    const keywordValue = keyword.trim()
    const result = await listMaterials({
      page: 1,
      size: 20,
      code: keywordValue || undefined,
      name: keywordValue || undefined,
      type: props.type
    })
    options.value = result.items
  } catch {
    // 统一拦截器已提示，保留当前选项
  } finally {
    loading.value = false
  }
}

async function ensureSelectedOption() {
  const code = props.modelValue
  if (!code) {
    return
  }
  if (options.value.some((item) => item.code === code)) {
    return
  }
  try {
    const material = await getMaterial(code)
    mergeOption(material)
  } catch {
    // 详情不存在时保留原始编码展示
  }
}

function onValueChange(value: string | null | undefined) {
  const next = value || null
  emit('update:modelValue', next)
  const material = options.value.find((item) => item.code === next) ?? null
  emit('change', material)
}

onMounted(async () => {
  await search('')
  await ensureSelectedOption()
})

watch(
  () => props.modelValue,
  () => {
    void ensureSelectedOption()
  }
)
</script>

<template>
  <el-select
    :model-value="selectedCode"
    filterable
    remote
    clearable
    :remote-method="search"
    :loading="loading"
    :placeholder="placeholder"
    :disabled="disabled"
    style="width: 100%"
    @update:model-value="onValueChange"
  >
    <el-option
      v-for="item in options"
      :key="item.code"
      :label="
        item.spec ? `${item.name} · ${item.spec}（${item.code}）` : `${item.name}（${item.code}）`
      "
      :value="item.code"
    />
  </el-select>
</template>
