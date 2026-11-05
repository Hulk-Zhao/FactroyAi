<script setup lang="ts">
withDefaults(
  defineProps<{
    rows?: number
    showTitle?: boolean
  }>(),
  {
    rows: 6,
    showTitle: true
  }
)
</script>

<template>
  <div class="page-skeleton" aria-busy="true" aria-label="内容加载中">
    <el-skeleton animated>
      <template #template>
        <el-skeleton-item v-if="showTitle" variant="h1" class="page-skeleton__title" />
        <el-skeleton-item variant="rect" class="page-skeleton__toolbar" />
        <el-skeleton-item v-for="row in rows" :key="row" variant="text" class="page-skeleton__row" />
      </template>
    </el-skeleton>
  </div>
</template>

<style scoped>
.page-skeleton {
  padding: var(--space-3) 0;
}

.page-skeleton__title {
  width: 180px;
  height: 24px;
  margin-bottom: var(--space-3);
}

.page-skeleton__toolbar {
  width: 100%;
  height: 48px;
  margin-bottom: var(--space-3);
}

.page-skeleton__row {
  width: 100%;
  height: 22px;
  margin-bottom: 10px;
}
</style>
