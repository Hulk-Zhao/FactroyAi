<script setup lang="ts">
import { computed } from 'vue'

import { StatusTag } from '@/components/common'
import type { Bom } from '@/types/boms'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    bom?: Bom | null
    materialNames?: Record<string, string>
  }>(),
  {
    bom: null,
    materialNames: () => ({})
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const items = computed(() => props.bom?.items ?? [])

function nameOf(code: string): string {
  return props.materialNames[code] ?? '物料名称待接入'
}

function scrapText(rate?: number): string {
  if (rate === undefined || rate === null) {
    return '无'
  }
  return `${(rate * 100).toFixed(2)}%`
}

function substitutesText(items?: string[]): string {
  if (!items || items.length === 0) {
    return '无'
  }
  return items
    .map((code) => {
      const name = props.materialNames[code]
      return name ? `${name}（${code}）` : code
    })
    .join('、')
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="BOM 明细"
    size="640px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="bom">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="父件物料名称">
          {{ nameOf(bom.parentMaterialCode) }}
        </el-descriptions-item>
        <el-descriptions-item label="父件物料编码">{{ bom.parentMaterialCode }}</el-descriptions-item>
        <el-descriptions-item label="BOM 版本">{{ bom.bomVersion }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag enum-key="bomStatus" :value="bom.status" />
        </el-descriptions-item>
        <el-descriptions-item label="生效日期">{{ bom.effectiveDate }}</el-descriptions-item>
        <el-descriptions-item label="失效日期">{{ bom.expiryDate || '无' }}</el-descriptions-item>
        <el-descriptions-item label="拼板方式">
          {{ bom.isPanel ? `是（${bom.panelSize ?? '无'} 拼）` : '否' }}
        </el-descriptions-item>
        <el-descriptions-item label="数据版本">{{ bom.version }}</el-descriptions-item>
      </el-descriptions>

      <div class="section-title">组件明细（{{ items.length }}）</div>
      <el-table :data="items" border size="small" row-key="materialCode">
        <el-table-column label="组件名称" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ nameOf(row.materialCode) }}</template>
        </el-table-column>
        <el-table-column prop="materialCode" label="物料编码" min-width="140">
          <template #default="{ row }">
            <span class="secondary-text">{{ row.materialCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="qtyPerUnit" label="单位用量" width="100" />
        <el-table-column label="损耗率" width="100">
          <template #default="{ row }">{{ scrapText(row.scrapRate) }}</template>
        </el-table-column>
        <el-table-column prop="position" label="位置" width="100" />
        <el-table-column label="替代料" min-width="140">
          <template #default="{ row }">{{ substitutesText(row.substitutes) }}</template>
        </el-table-column>
        <el-table-column label="虚拟件" width="90">
          <template #default="{ row }">{{ row.isPhantom ? '是' : '否' }}</template>
        </el-table-column>
      </el-table>

      <div class="drawer-actions">
        <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      </div>
    </template>
    <el-empty v-else description="未选择 BOM" />
  </el-drawer>
</template>

<style scoped>
.secondary-text {
  color: var(--text-3);
  font-size: var(--font-xs);
}

.section-title {
  margin: 20px 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 24px;
}
</style>
