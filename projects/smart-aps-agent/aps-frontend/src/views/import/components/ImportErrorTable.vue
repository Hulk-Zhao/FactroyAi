<script setup lang="ts">
import { computed, ref } from 'vue'

import { importColumnLabel } from '@/constants/import'
import type { ImportError } from '@/types/import'

const props = defineProps<{
  errors: ImportError[]
  entity: string
}>()

const selectedIndex = ref(-1)

const selectedError = computed(() =>
  selectedIndex.value >= 0 ? props.errors[selectedIndex.value] ?? null : null
)

function fieldLabel(field: string): string {
  return importColumnLabel(props.entity, field)
}

function rowClassName({ rowIndex }: { rowIndex: number }): string {
  return rowIndex === selectedIndex.value ? 'error-row-selected' : ''
}

function onRowClick(row: ImportError): void {
  selectedIndex.value = props.errors.indexOf(row)
}
</script>

<template>
  <div class="error-table">
    <el-empty v-if="errors.length === 0" :image-size="56" description="无错误行" />

    <template v-else>
      <el-alert
        v-if="selectedError"
        class="locate"
        type="warning"
        :closable="false"
        show-icon
        :title="`已定位：Excel 第 ${selectedError.row} 行 · ${fieldLabel(selectedError.field)}（${selectedError.field}）`"
      >
        {{ selectedError.message }}
      </el-alert>

      <el-table
        :data="errors"
        size="small"
        border
        :row-class-name="rowClassName"
        @row-click="onRowClick"
      >
        <el-table-column label="行号" prop="row" width="90" />
        <el-table-column label="字段" width="180">
          <template #default="{ row }">
            <span class="field-header">{{ fieldLabel(row.field) }}</span>
            <span class="field-key">{{ row.field }}</span>
          </template>
        </el-table-column>
        <el-table-column label="错误码" prop="code" width="100" />
        <el-table-column label="提示" prop="message" min-width="220" show-overflow-tooltip />
      </el-table>
      <p class="locate-tip">点击任意错误行可定位到 Excel 物理行号与列键。</p>
    </template>
  </div>
</template>

<style scoped>
.error-table {
  margin-top: var(--space-3);
}

.locate {
  margin-bottom: var(--space-2);
}

.field-header {
  color: var(--text);
}

.field-key {
  margin-left: var(--space-2);
  color: var(--text-3);
  font-family: var(--font-mono, monospace);
  font-size: var(--font-xs);
}

.locate-tip {
  margin: var(--space-2) 0 0;
  color: var(--text-3);
  font-size: var(--font-xs);
}

:deep(.error-row-selected) td {
  background: var(--warn-bg) !important;
}
</style>
