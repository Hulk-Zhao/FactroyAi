<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { fetchHealth } from '@/api'
import { PageSkeleton } from '@/components/common'
import type { Health } from '@/types/api'

const health = ref<Health | null>(null)
const loading = ref(false)
const error = ref('')

onMounted(async () => {
  loading.value = true
  try {
    health.value = await fetchHealth()
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '健康检查失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <h2 class="page-title">工作台</h2>

    <!-- 首屏骨架：健康检查返回前先给出页面结构，避免白屏（M3 性能整改）。 -->
    <PageSkeleton v-if="loading" :rows="7" :show-title="false" />

    <template v-else>
      <el-card class="page-card" shadow="never">
        <template #header>后端连接状态</template>
        <el-result
          v-if="health"
          icon="success"
          title="后端服务可用"
          :sub-title="`服务：${health.service} · 状态：${health.status}`"
        />
        <el-alert v-else type="error" :closable="false" :title="error || '尚未连接后端'" />
      </el-card>

      <el-card shadow="never">
        <template #header>MVP 基座能力</template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="后端分层"
            >web → service → domain / solver-api / infrastructure</el-descriptions-item
          >
          <el-descriptions-item label="求解引擎"
            >Timefold（默认）/ OR-Tools（预留）</el-descriptions-item
          >
          <el-descriptions-item label="领域模块"
            >基础数据、订单、排产、甘特图规划中</el-descriptions-item
          >
          <el-descriptions-item label="前端技术栈"
            >Vue 3 + TypeScript + Vite + Element Plus</el-descriptions-item
          >
        </el-descriptions>
      </el-card>
    </template>
  </div>
</template>
