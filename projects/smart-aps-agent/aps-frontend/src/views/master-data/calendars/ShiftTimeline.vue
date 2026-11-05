<script setup lang="ts">
import { computed } from 'vue'

import type { Shift } from '@/types/calendars'

const props = defineProps<{
  shifts: Shift[]
}>()

const dayLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const hourMarks = [0, 6, 12, 18, 24]

interface Segment {
  day: number
  left: number
  width: number
  overnight: boolean
  colorIndex: number
}

function toMinutes(value: string): number {
  const [hour, minute] = value.split(':').map((item) => Number(item))
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) {
    return 0
  }
  return hour * 60 + minute
}

/** 单班次拆成 0-24 点的可视片段；end 小于 start 视为跨零点，拆为两段。 */
const segments = computed<Segment[]>(() => {
  const result: Segment[] = []
  props.shifts.forEach((shift, index) => {
    const start = toMinutes(shift.startTime)
    const end = toMinutes(shift.endTime)
    if (start === end) {
      return
    }
    shift.daysOfWeek.forEach((day) => {
      if (end > start) {
        result.push({
          day,
          left: (start / 1440) * 100,
          width: ((end - start) / 1440) * 100,
          overnight: false,
          colorIndex: index
        })
        return
      }
      result.push({
        day,
        left: (start / 1440) * 100,
        width: ((1440 - start) / 1440) * 100,
        overnight: true,
        colorIndex: index
      })
      result.push({
        day,
        left: 0,
        width: (end / 1440) * 100,
        overnight: true,
        colorIndex: index
      })
    })
  })
  return result
})

function segmentsOfDay(day: number): Segment[] {
  return segments.value.filter((segment) => segment.day === day)
}

function colorOf(index: number): string {
  const palette = [
    'var(--primary)',
    'var(--ok)',
    'var(--warn)',
    'var(--danger)',
    'var(--text-3)',
    'var(--accent)'
  ]
  return palette[index % palette.length]
}
</script>

<template>
  <div class="timeline">
    <div class="timeline-header">
      <span class="day-column" />
      <div class="hours">
        <span
          v-for="hour in hourMarks"
          :key="hour"
          class="hour"
          :style="{ left: `${(hour / 24) * 100}%` }"
        >
          {{ hour }}:00
        </span>
      </div>
    </div>
    <div v-for="day in 7" :key="day" class="timeline-row">
      <span class="day-column">{{ dayLabels[day - 1] }}</span>
      <div class="track">
        <span
          v-for="hour in hourMarks"
          :key="hour"
          class="grid-line"
          :style="{ left: `${(hour / 24) * 100}%` }"
        />
        <span
          v-for="(segment, index) in segmentsOfDay(day)"
          :key="`${day}-${index}`"
          class="bar"
          :style="{
            left: `${segment.left}%`,
            width: `${segment.width}%`,
            backgroundColor: colorOf(segment.colorIndex)
          }"
          :title="segment.overnight ? '跨零点班次' : '班次时段'"
        />
      </div>
    </div>
    <div class="legend">
      <span class="legend-item">
        <i class="legend-dot" style="background-color: var(--primary)" />
        同一颜色代表同一班次；跨零点班次会拆成两段显示。
      </span>
    </div>
  </div>
</template>

<style scoped>
.timeline {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px;
  background: var(--card);
}

.timeline-header,
.timeline-row {
  display: flex;
  align-items: center;
}

.timeline-header {
  color: var(--text-3);
  font-size: 12px;
  margin-bottom: 4px;
}

.day-column {
  width: 44px;
  flex: 0 0 44px;
  font-size: 12px;
  color: var(--text-2);
}

.hours {
  flex: 1;
  position: relative;
  height: 18px;
}

.hour {
  position: absolute;
  transform: translateX(-50%);
}

.hours .hour {
  white-space: nowrap;
}

.timeline-row {
  margin-bottom: 6px;
}

.track {
  position: relative;
  flex: 1;
  height: 22px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 3px;
  overflow: hidden;
}

.grid-line {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background: var(--border);
}

.bar {
  position: absolute;
  top: 3px;
  bottom: 3px;
  border-radius: 3px;
  opacity: 0.85;
}

.legend {
  margin-top: 8px;
  color: var(--text-3);
  font-size: 12px;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
}
</style>
