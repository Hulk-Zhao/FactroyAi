<script setup lang="ts">
import { CustomChart } from 'echarts/charts'
import { DataZoomComponent, GridComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { getEnumLabel } from '@/constants/enums'
import type { GanttBarModel, GanttDataset, GanttZoom } from './index'

echarts.use([CustomChart, GridComponent, TooltipComponent, DataZoomComponent, CanvasRenderer])

const props = withDefaults(
  defineProps<{
    dataset: GanttDataset
    zoom: GanttZoom
    rowHeight?: number
  }>(),
  { rowHeight: 28 }
)

const containerRef = ref<HTMLElement | null>(null)
const zoomPercent: Record<GanttZoom, number> = { hour: 15, day: 45, week: 100, month: 100 }

const tokenCache = new Map<string, string>()

/** 设计令牌 → 具体色值：canvas 无法直接使用 var()，统一走 :root 解析。 */
function tokenColor(name: string): string {
  let value = tokenCache.get(name)
  if (value === undefined) {
    value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
    tokenCache.set(name, value)
  }
  return value
}

let chart: ReturnType<typeof echarts.init> | null = null
let resizeObserver: ResizeObserver | null = null

interface CustomItem {
  value: [number, number, number]
  itemStyle: { color: string }
  task: GanttBarModel
  kind: 'run' | 'setup'
}

interface RenderApi {
  value: (index: number) => number
  coord: (point: [number, number]) => [number, number]
  size: (range: [number, number]) => [number, number]
}

function formatTime(value?: string | null): string {
  if (!value) {
    return '—'
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function tooltipFormatter(params: { data?: CustomItem }): string {
  const item = params.data
  if (!item) {
    return ''
  }
  const task = item.task
  return [
    `<strong>${task.orderNo} · 工序 ${task.operationIndex ?? '—'}</strong>`,
    `资源：${task.resourceCode ?? '—'}`,
    `${item.kind === 'setup' ? '换型' : '加工'}：${formatTime(item.kind === 'setup' ? task.setupStartTime : task.startTime)} → ${formatTime(item.kind === 'setup' ? task.setupEndTime : task.endTime)}`,
    `状态：${getEnumLabel('taskStatus', task.status)} · 版本 ${task.version}`
  ].join('<br/>')
}

function buildOption(): Record<string, unknown> {
  const rows = props.dataset.rows
  const runData: CustomItem[] = []
  const setupData: CustomItem[] = []
  rows.forEach((row, rowIndex) => {
    row.bars.forEach((task) => {
      runData.push({
        value: [rowIndex, Date.parse(task.startTime), Date.parse(task.endTime)],
        itemStyle: { color: tokenColor('--gantt-bar-run') },
        task,
        kind: 'run'
      })
      if (task.setupStartTime && task.setupEndTime) {
        const setupStart = Date.parse(task.setupStartTime)
        const setupEnd = Date.parse(task.setupEndTime)
        if (setupEnd > setupStart) {
          setupData.push({
            value: [rowIndex, setupStart, setupEnd],
            itemStyle: { color: tokenColor('--gantt-setup-stripe') },
            task,
            kind: 'setup'
          })
        }
      }
    })
  })

  const renderItem = (params: unknown, api: RenderApi) => {
    const rowIndex = api.value(0)
    const start = api.coord([api.value(1), rowIndex])
    const end = api.coord([api.value(2), rowIndex])
    const rowSize = api.size([0, 1])
    const height = Math.max(rowSize[1] * 0.45, 6)
    return {
      type: 'rect',
      shape: {
        x: start[0],
        y: start[1] - height / 2,
        width: Math.max(end[0] - start[0], 1),
        height
      },
      style: {
        fill: (params as { data?: { itemStyle?: { color?: string } } }).data?.itemStyle?.color
      }
    }
  }

  return {
    animation: false,
    grid: { left: 8, right: 24, top: 16, bottom: 56, containLabel: true },
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: tooltipFormatter
    },
    xAxis: {
      type: 'time',
      min: Date.parse(props.dataset.range.from),
      max: Date.parse(props.dataset.range.to),
      axisLabel: { hideOverlap: true, fontSize: 10 }
    },
    yAxis: {
      type: 'category',
      inverse: true,
      data: rows.map((row) => `${row.label} (${row.key})`),
      axisLabel: { fontSize: 11, width: 190, overflow: 'truncate' }
    },
    dataZoom: [
      { type: 'inside', start: 0, end: zoomPercent[props.zoom] },
      { type: 'slider', start: 0, end: zoomPercent[props.zoom], height: 16, bottom: 8 }
    ],
    series: [
      {
        name: '加工窗口',
        type: 'custom',
        renderItem,
        encode: { x: [1, 2], y: 0 },
        data: runData,
        clip: true
      },
      {
        name: '换型窗口',
        type: 'custom',
        renderItem,
        encode: { x: [1, 2], y: 0 },
        data: setupData,
        clip: true
      }
    ]
  }
}

function render(): void {
  if (!containerRef.value) {
    return
  }
  if (!chart) {
    chart = echarts.init(containerRef.value, undefined, { renderer: 'canvas' })
  } else {
    chart.resize()
  }
  chart.setOption(buildOption(), true)
}

/** 首帧后容器宽高可能尚未稳定，等两帧布局稳定后再初始化，避免 custom series 首次绘制为空。 */
function scheduleRender(): void {
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      render()
      window.setTimeout(() => chart?.resize(), 120)
    })
  })
}

onMounted(() => {
  scheduleRender()
  if (containerRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => chart?.resize())
    resizeObserver.observe(containerRef.value)
  }
})

watch(
  () => [props.dataset, props.zoom],
  () => scheduleRender(),
  { deep: false }
)

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div
    class="gantt-echarts"
    :style="{ height: `${props.dataset.rows.length * props.rowHeight + 96}px` }"
  >
    <div ref="containerRef" class="gantt-echarts__canvas" />
  </div>
</template>

<style scoped>
.gantt-echarts {
  width: 100%;
  border: var(--border-width) solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--card);
  overflow: hidden;
}

.gantt-echarts__canvas {
  width: 100%;
  height: 100%;
}
</style>
