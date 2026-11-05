<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    state: 'loading' | 'empty' | 'error'
    title?: string
    description?: string
    retryText?: string
  }>(),
  {
    title: '',
    description: '',
    retryText: '重试'
  }
)

const emit = defineEmits<{ retry: [] }>()

const errorTitle = computed(() => props.title || '加载失败')
const descriptionText = computed(
  () => props.description || (props.state === 'empty' ? '暂无数据' : '请稍后重试')
)
</script>

<template>
  <div class="page-state">
    <el-skeleton v-if="state === 'loading'" :rows="4" animated />
    <el-empty v-else-if="state === 'empty'" :description="descriptionText" />
    <el-result v-else icon="error" :title="errorTitle" :sub-title="descriptionText">
      <template #extra>
        <el-button type="primary" @click="emit('retry')">{{ retryText }}</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.page-state {
  padding: var(--space-4) 0;
}
</style>
