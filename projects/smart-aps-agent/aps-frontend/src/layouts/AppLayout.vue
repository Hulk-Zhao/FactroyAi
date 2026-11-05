<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import HelpAssistant from '@/components/common/HelpAssistant.vue'
import ThemeToggle from '@/components/common/ThemeToggle.vue'
import { menuModules } from '@/router/modules'
import { useTraceStore } from '@/stores/trace'

import AppBreadcrumb from './components/AppBreadcrumb.vue'

const route = useRoute()
const traceStore = useTraceStore()

const activeMenu = computed(() => route.path)
const traceText = computed(() =>
  traceStore.latestTraceId ? traceStore.latestTraceId.slice(0, 12) : '暂无请求'
)

async function copyTraceId() {
  if (!traceStore.latestTraceId) {
    ElMessage.info('当前还没有请求链路 ID')
    return
  }
  try {
    await globalThis.navigator.clipboard.writeText(traceStore.latestTraceId)
    ElMessage.success('traceId 已复制')
  } catch {
    ElMessage.warning(`复制失败，traceId：${traceStore.latestTraceId}`)
  }
}
</script>

<template>
  <div class="app-shell">
    <el-container class="layout">
      <el-aside width="var(--sidebar-w)" class="aside">
        <div class="brand">
          <span class="brand-logo" aria-hidden="true">A</span>
          <span class="brand-name">智能APS</span>
        </div>
        <div class="menu-scroll">
          <el-menu
            :default-active="activeMenu"
            :default-openeds="['master-data']"
            router
            class="menu"
          >
            <template v-for="module in menuModules" :key="module.key">
              <el-sub-menu v-if="module.menuItems?.length" :index="module.key">
                <template #title>{{ module.label }}</template>
                <el-menu-item
                  v-for="item in module.menuItems"
                  :key="item.path"
                  :index="item.path"
                >
                  {{ item.label }}
                </el-menu-item>
              </el-sub-menu>
              <el-menu-item v-else :index="module.menuPath">{{ module.label }}</el-menu-item>
            </template>
          </el-menu>
        </div>
      </el-aside>

      <el-container class="workspace">
        <el-header class="header">
          <AppBreadcrumb />
          <div class="header-actions">
            <ThemeToggle />
            <div class="trace">
              <span class="trace-label">traceId</span>
              <el-tag size="small" type="info" effect="plain">{{ traceText }}</el-tag>
              <el-button link type="primary" @click="copyTraceId">复制</el-button>
            </div>
          </div>
        </el-header>
        <el-main class="content">
          <RouterView />
        </el-main>
      </el-container>
    </el-container>

    <HelpAssistant />
  </div>
</template>

<style scoped>
.app-shell {
  height: 100%;
}

/* 外壳固定：侧栏不参与滚动，滚动只发生在内容区 */
.layout {
  height: 100vh;
  overflow: hidden;
  background: var(--bg);
}

.aside {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  background: var(--sidebar-bg);
  border-right: var(--border-width) solid var(--sidebar-border);
}

.brand {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  height: var(--topbar-h);
  color: var(--text);
  font-size: var(--font-lg);
  font-weight: 600;
  letter-spacing: var(--tracking-brand);
  border-bottom: var(--border-width) solid var(--sidebar-border);
}

.brand-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: linear-gradient(145deg, var(--primary-hover), var(--primary-dark));
  color: #ffffff;
  font-size: var(--font-sm);
  font-weight: 700;
  letter-spacing: 0;
}

.menu-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
}

.menu {
  border-right: none;
  background: var(--sidebar-bg);
}

.menu :deep(.el-menu-item),
.menu :deep(.el-sub-menu__title) {
  color: var(--sidebar-text);
}

.menu :deep(.el-menu-item.is-active) {
  color: var(--sidebar-text-active);
  background: var(--primary-light);
}

.menu :deep(.el-menu-item:hover),
.menu :deep(.el-sub-menu__title:hover) {
  background: var(--primary-bg);
}

.workspace {
  display: flex;
  flex-direction: column;
  height: 100vh;
  min-width: 0;
  overflow: hidden;
}

.header {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  height: var(--topbar-h);
  background: var(--card);
  border-bottom: var(--border-width) solid var(--border);
}

.content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.trace {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.trace-label {
  color: var(--text-3);
  font-size: var(--font-xs);
}

/* 窄屏顶栏收缩：隐藏 traceId 文字标签，截断链路 ID，压缩左右内边距 */
@media (max-width: 1439px) {
  .trace :deep(.el-tag) {
    max-width: 120px;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

@media (max-width: 1023px) {
  .header {
    gap: var(--space-2);
    padding: 0 var(--space-3);
  }

  .trace {
    display: none;
  }
}
</style>
