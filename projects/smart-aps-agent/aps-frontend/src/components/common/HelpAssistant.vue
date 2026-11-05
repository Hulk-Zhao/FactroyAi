<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'

import { resolveHelpEntry } from '@/constants/help'
import { helpContextKey } from '@/constants/help/context'
import type { HelpModule, HelpScene } from '@/constants/help/types'

const OPEN_KEY = 'aps-help-open'
const POS_KEY = 'aps-help-position'
const FAB_SIZE = 48

const route = useRoute()
const entry = computed(() => resolveHelpEntry(route.path, typeof route.name === 'string' ? route.name : null))

/** 窄屏（<640px）默认收起：面板近全宽会压住主操作按钮，因此不恢复"打开"状态，点击入口才展开。 */
const narrow = ref(window.innerWidth < 640)
const open = ref(!narrow.value && readOpen())
const loading = ref(false)
const error = ref('')
const sceneModule = shallowRef<HelpModule | null>(null)
const panelRef = ref<HTMLElement | null>(null)

/** 页面内上下文（如详情抽屉里的 AI 解读）优先于路由级场景，但只能命中当前模块已有的键。 */
const activeKey = computed(() => {
  const context = helpContextKey.value
  if (context && sceneModule.value && context in sceneModule.value) {
    return context
  }
  return entry.value?.key ?? ''
})
const scene = computed<HelpScene | null>(() =>
  sceneModule.value ? sceneModule.value[activeKey.value] ?? null : null
)

/** 帮助面板当前展示的是页面级场景，还是页面内某个组件（如 AI 解读/交期预估）的上下文场景。 */
const contextActive = computed(() => activeKey.value !== (entry.value?.key ?? ''))
const customPosition = ref(readPosition())
const viewport = ref({ width: window.innerWidth, height: window.innerHeight })
const isDragging = ref(false)

let dragState: { startX: number; startY: number; originX: number; originY: number; moved: boolean } | null = null
let suppressClick = false
let cache: { key: string; module: HelpModule } | null = null

function readOpen(): boolean {
  try {
    return localStorage.getItem(OPEN_KEY) === '1'
  } catch {
    return false
  }
}

function readPosition(): { x: number; y: number } | null {
  try {
    const raw = localStorage.getItem(POS_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as { x?: unknown; y?: unknown }
    if (typeof parsed.x === 'number' && typeof parsed.y === 'number') {
      return { x: parsed.x, y: parsed.y }
    }
  } catch {
    return null
  }
  return null
}

function savePosition() {
  if (!customPosition.value) return
  try {
    localStorage.setItem(POS_KEY, JSON.stringify(customPosition.value))
  } catch {
    // localStorage 不可写时仅保留内存态。
  }
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

function defaultFab() {
  return {
    x: viewport.value.width - 24 - FAB_SIZE,
    y: viewport.value.height - 24 - FAB_SIZE
  }
}

const fabStyle = computed(() => {
  const position = customPosition.value
  if (!position) {
    return { right: '24px', bottom: '24px' }
  }
  return { left: `${position.x}px`, top: `${position.y}px` }
})

const panelStyle = computed(() => {
  const { width, height } = viewport.value
  const panelWidth = width < 640 ? width - 48 : Math.min(300, width - 24)
  const base = { width: `${panelWidth}px`, maxHeight: `min(70vh, 560px)` }
  const position = customPosition.value
  if (!position) {
    return { ...base, right: '24px', bottom: '84px' }
  }
  const left = clamp(position.x + FAB_SIZE - panelWidth, 12, Math.max(12, width - panelWidth - 12))
  if (position.y > 360) {
    return {
      ...base,
      left: `${left}px`,
      bottom: `${height - position.y + 12}px`,
      maxHeight: `${Math.max(240, position.y - 24)}px`
    }
  }
  return {
    ...base,
    left: `${left}px`,
    top: `${position.y + FAB_SIZE + 12}px`,
    maxHeight: `${Math.max(220, height - position.y - FAB_SIZE - 36)}px`
  }
})

async function loadScene() {
  const current = entry.value
  if (!current) return
  if (cache && cache.key === current.key) {
    sceneModule.value = cache.module
    if (!scene.value) {
      error.value = '该场景尚未配置帮助内容。'
    }
    return
  }
  loading.value = true
  error.value = ''
  try {
    const module = await current.load()
    cache = { key: current.key, module: module.default }
    sceneModule.value = module.default
    if (!scene.value) {
      error.value = '该场景尚未配置帮助内容。'
    }
  } catch {
    error.value = '帮助内容加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function openPanel() {
  if (!entry.value) return
  open.value = true
  await loadScene()
  await nextTick()
  panelRef.value?.focus()
}

function closePanel() {
  open.value = false
}

function togglePanel() {
  if (open.value) closePanel()
  else void openPanel()
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && open.value) {
    closePanel()
  }
}

function startDrag(event: PointerEvent) {
  event.preventDefault()
  const origin = customPosition.value ?? defaultFab()
  dragState = {
    startX: event.clientX,
    startY: event.clientY,
    originX: origin.x,
    originY: origin.y,
    moved: false
  }
  isDragging.value = true
  window.addEventListener('pointermove', onDrag)
  window.addEventListener('pointerup', endDrag, { once: true })
}

function onDrag(event: PointerEvent) {
  if (!dragState) return
  const dx = event.clientX - dragState.startX
  const dy = event.clientY - dragState.startY
  if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
    dragState.moved = true
  }
  customPosition.value = {
    x: clamp(dragState.originX + dx, 8, viewport.value.width - FAB_SIZE - 8),
    y: clamp(dragState.originY + dy, 8, viewport.value.height - FAB_SIZE - 8)
  }
}

function endDrag() {
  window.removeEventListener('pointermove', onDrag)
  if (dragState?.moved) {
    suppressClick = true
    savePosition()
  }
  dragState = null
  isDragging.value = false
}

function onFabClick() {
  if (suppressClick) {
    suppressClick = false
    return
  }
  togglePanel()
}

function onResize() {
  viewport.value = { width: window.innerWidth, height: window.innerHeight }
  narrow.value = window.innerWidth < 640
  if (narrow.value && open.value) {
    closePanel()
  }
  if (customPosition.value) {
    customPosition.value = {
      x: clamp(customPosition.value.x, 8, viewport.value.width - FAB_SIZE - 8),
      y: clamp(customPosition.value.y, 8, viewport.value.height - FAB_SIZE - 8)
    }
  }
}

watch(open, (value) => {
  syncReservedSpace()
  try {
    localStorage.setItem(OPEN_KEY, value ? '1' : '0')
  } catch {
    // ignore
  }
})

watch(customPosition, syncReservedSpace)

/** 面板默认停靠右缘时，让内容区预留等宽空隙，保证不遮住甘特与操作区；拖动后不再预留。 */
function syncReservedSpace() {
  document.body.classList.toggle('help-assistant-open', open.value && !customPosition.value)
}

watch(
  () => [route.path, route.name] as const,
  async () => {
    sceneModule.value = null
    helpContextKey.value = null
    error.value = ''
    if (!entry.value) return
    if (open.value) {
      await loadScene()
    }
  }
)

/** 页面内上下文切换（如打开 AI 解读/交期预估）时，若面板已打开则直接换场景。 */
watch(helpContextKey, () => {
  if (scene.value) {
    error.value = ''
  }
})

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', onResize)
  if (open.value) {
    void loadScene()
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', onResize)
  window.removeEventListener('pointermove', onDrag)
  document.body.classList.remove('help-assistant-open')
})
</script>

<template>
  <div v-if="entry" class="help-assistant">
    <button
      type="button"
      class="help-fab"
      :class="{ 'is-dragging': isDragging }"
      :style="fabStyle"
      aria-label="打开当前场景帮助"
      :aria-expanded="open"
      @pointerdown="startDrag"
      @click="onFabClick"
    >
      ?
    </button>

    <aside
      v-if="open"
      ref="panelRef"
      class="help-panel"
      :style="panelStyle"
      role="dialog"
      aria-modal="false"
      :aria-label="scene ? `场景帮助：${scene.title}` : '场景帮助'"
      tabindex="-1"
    >
      <header class="help-panel__head">
        <div>
          <span class="help-panel__badge">{{ contextActive ? '当前操作' : '当前页面' }}</span>
          <h3>{{ scene?.title ?? '场景帮助' }}</h3>
          <p v-if="scene">{{ scene.summary }}</p>
        </div>
        <button type="button" class="help-close" aria-label="关闭帮助" @click="closePanel">×</button>
      </header>

      <div v-if="loading" class="help-state">正在加载帮助内容…</div>
      <div v-else-if="error" class="help-state">{{ error }}</div>
      <div v-else-if="scene" class="help-panel__body">
        <section v-for="section in scene.sections" :key="section.title" class="help-section">
          <h4>{{ section.title }}</h4>
          <p v-if="section.body">{{ section.body }}</p>
          <ul v-if="section.items?.length">
            <li v-for="item in section.items" :key="item">{{ item }}</li>
          </ul>
        </section>
      </div>

      <footer class="help-panel__foot">按 Esc 关闭 · 拖动右下角问号可移动入口</footer>
    </aside>
  </div>
</template>

<style scoped>
.help-assistant {
  position: static;
}

.help-fab {
  position: fixed;
  z-index: 2000;
  width: 48px;
  height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--primary);
  border-radius: 50%;
  background: var(--card);
  color: var(--primary);
  box-shadow: var(--shadow-md);
  font-family: inherit;
  font-size: var(--font-lg);
  font-weight: 700;
  cursor: grab;
  touch-action: none;
}

.help-fab:hover {
  background: var(--primary-light);
}

.help-fab.is-dragging {
  cursor: grabbing;
}

.help-panel {
  position: fixed;
  z-index: 2001;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card);
  box-shadow: var(--shadow-pop);
  outline: none;
}

.help-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--border);
  background: var(--bg-soft);
}

.help-panel__badge {
  display: inline-block;
  margin-bottom: var(--space-1);
  padding: 0 6px;
  border: 1px solid color-mix(in srgb, var(--primary) 45%, transparent);
  border-radius: 999px;
  color: var(--primary);
  font-size: var(--font-2xs);
  line-height: 16px;
}

.help-panel__head h3 {
  margin: 0;
  color: var(--text);
  font-size: var(--font-base);
  font-weight: 600;
}

.help-panel__head p {
  margin: var(--space-1) 0 0;
  color: var(--text-3);
  font-size: var(--font-xs);
  line-height: 1.55;
}

.help-close {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-2);
  font-size: var(--font-lg);
  line-height: 1;
  cursor: pointer;
}

.help-close:hover {
  border-color: var(--border);
  background: var(--card);
  color: var(--text);
}

.help-panel__body {
  overflow-y: auto;
  padding: var(--space-3) var(--space-4);
}

.help-section + .help-section {
  margin-top: var(--space-3);
}

.help-section h4 {
  margin: 0 0 var(--space-1);
  color: var(--text);
  font-size: var(--font-sm);
  font-weight: 600;
}

.help-section p {
  margin: 0;
  color: var(--text-2);
  font-size: var(--font-sm);
  line-height: 1.7;
}

.help-section ul {
  margin: 0;
  padding-left: 18px;
  color: var(--text-2);
  font-size: var(--font-sm);
  line-height: 1.7;
}

.help-state {
  padding: var(--space-6) var(--space-4);
  color: var(--text-3);
  font-size: var(--font-sm);
  text-align: center;
}

.help-panel__foot {
  padding: var(--space-2) var(--space-4);
  border-top: 1px solid var(--border);
  color: var(--text-3);
  font-size: var(--font-2xs);
}

@media (max-width: 640px) {
  .help-fab {
    width: 44px;
    height: 44px;
    font-size: var(--font-base);
  }
}
</style>
