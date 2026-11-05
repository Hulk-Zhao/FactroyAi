<script setup lang="ts">
import { computed } from 'vue'

import PageSkeleton from './PageSkeleton.vue'
import PageState from './PageState.vue'

const props = withDefaults(
  defineProps<{
    data: unknown[]
    loading?: boolean
    error?: string
    page?: number
    size?: number
    total?: number
    rowKey?: string
    emptyText?: string
    pageSizes?: number[]
    showPagination?: boolean
  }>(),
  {
    loading: false,
    error: '',
    page: 1,
    size: 20,
    total: 0,
    rowKey: 'id',
    emptyText: '暂无数据',
    pageSizes: () => [10, 20, 50, 100],
    showPagination: true
  }
)

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  change: [page: number, size: number]
  retry: []
}>()

const tableData = computed(() => props.data as Record<string, unknown>[])

function onPageChange(page: number) {
  emit('update:page', page)
  emit('change', page, props.size)
}

function onSizeChange(size: number) {
  emit('update:size', size)
  emit('change', 1, size)
}
</script>

<template>
  <el-card shadow="never" class="data-table">
    <div v-if="$slots.toolbar" class="toolbar">
      <slot name="toolbar" />
    </div>

    <PageState v-if="error" state="error" :description="error" @retry="emit('retry')" />

    <!-- 首次加载且无数据时用骨架屏替代空白表格，避免白屏（M3 性能整改）。 -->
    <PageSkeleton v-else-if="loading && tableData.length === 0" :rows="8" :show-title="false" />

    <el-table v-else v-loading="loading" :data="tableData" :row-key="rowKey" border stripe>
      <slot />
      <template #empty>
        <PageState state="empty" :description="emptyText" />
      </template>
    </el-table>

    <div v-if="showPagination" class="pagination">
      <el-pagination
        background
        layout="total, sizes, prev, pager, next, jumper"
        :current-page="page"
        :page-size="size"
        :page-sizes="pageSizes"
        :total="total"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>
  </el-card>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}
</style>
