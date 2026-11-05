<script setup lang="ts">
import { computed } from 'vue'

import { useThemeStore, type ThemeMode } from '@/stores/theme'

const themeStore = useThemeStore()

const mode = computed<ThemeMode>({
  get: () => themeStore.mode,
  set: (value) => themeStore.setMode(value)
})
</script>

<template>
  <el-select v-model="mode" size="small" class="theme-toggle" aria-label="主题模式">
    <el-option label="跟随系统" value="system" />
    <el-option label="浅色" value="light" />
    <el-option label="深色" value="dark" />
  </el-select>
</template>

<style scoped>
.theme-toggle {
  width: 104px;
}

@media (max-width: 1023px) {
  .theme-toggle {
    width: 84px;
  }
}
</style>
