/**
 * 资源日历 → 甘特停机窗口 / 负载分母（M2-FE-01 修正）。
 *
 * 事实来源：C14 资源列表的 calendarId + GET /calendars/{id}（班次 / 例外日 / 生效期）。
 * 禁止：用任务间隔推定停机，或用固定 24h 作为负载分母。
 *
 * 契约缺口（前端不造字段，仅在页面与报告中标注）：
 * - C4 /gantt/resource 不返回 calendarId，需前端二次查询；
 * - C14 未定义时区字段，班次为工厂墙钟；本实现按浏览器时区解释并在页面明示；
 * - C14 未定义资源未绑日历时是否继承资源组/全厂默认，本实现不做静默回落。
 */
import type { Calendar, Holiday, Shift } from '@/types/calendars'
import type { GanttCalendarBinding, GanttCalendarBindingState } from '@/types/gantt'

const MINUTE_MS = 60_000
const DAY_MS = 86_400_000

export const GANTT_CALENDAR_TIMEZONE = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

export const GANTT_CALENDAR_TIMEZONE_NOTE = `班次按工厂墙钟解释，前端按浏览器时区 ${GANTT_CALENDAR_TIMEZONE} 换算；C14 未定义时区字段（契约缺口）`

export type GanttCalendarWindowKind = 'HOLIDAY' | 'NON_WORKDAY' | 'OFF_SHIFT'

export interface GanttCalendarWindow {
  rowKey: string
  startTime: string
  endTime: string
  kind: GanttCalendarWindowKind
  reason: string
}

export interface GanttWorkingSegment {
  start: number
  end: number
}

export interface GanttCalendarDay {
  rowKey: string
  /** 本地自然日边界（与班次墙钟同一解释）。 */
  dayStart: string
  dayEnd: string
  /** 与甘特时间窗裁剪后的有效区间。 */
  effectiveStart: string
  effectiveEnd: string
  workingMinutes: number
  workingSegments: GanttWorkingSegment[]
  holidayName?: string
  makeup: boolean
  /** false 表示该日超出日历 effectiveFrom/effectiveTo。 */
  effective: boolean
}

export interface GanttCalendarRowState {
  rowKey: string
  state: GanttCalendarBindingState
  calendarId: string | null
  calendarName: string | null
  message: string
  coveredDays: number
  uncoveredDays: number
  makeupAssumptionDays: number
}

export interface GanttCalendarSummary {
  boundCount: number
  unboundCount: number
  missingCount: number
  errorCount: number
  coveredDays: number
  uncoveredDays: number
  windowCount: number
  heatComparableCount: number
  timezone: string
}

export interface GanttCalendarComputation {
  windows: GanttCalendarWindow[]
  days: GanttCalendarDay[]
  rowStates: GanttCalendarRowState[]
  summary: GanttCalendarSummary
}

function localDayStart(milliseconds: number): number {
  const date = new Date(milliseconds)
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

function addLocalDays(milliseconds: number, days: number): number {
  const date = new Date(milliseconds)
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days).getTime()
}

function localDateString(milliseconds: number): string {
  const date = new Date(milliseconds)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
    date.getDate()
  ).padStart(2, '0')}`
}

/** JS getDay：0=周日；C14 daysOfWeek：1=周一 ... 7=周日。 */
function isoDayOfWeek(milliseconds: number): number {
  const day = new Date(milliseconds).getDay()
  return day === 0 ? 7 : day
}

function weekdayLabel(day: number): string {
  return ['周一', '周二', '周三', '周四', '周五', '周六', '周日'][day - 1] ?? `周${day}`
}

function parseTimeToMinutes(value: string): number | null {
  if (value === '24:00') {
    return 24 * 60
  }
  const match = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(value)
  return match ? Number(match[1]) * 60 + Number(match[2]) : null
}

function mergeSegments(segments: GanttWorkingSegment[]): GanttWorkingSegment[] {
  const sorted = [...segments].sort((left, right) => left.start - right.start)
  const merged: GanttWorkingSegment[] = []
  sorted.forEach((segment) => {
    const last = merged[merged.length - 1]
    if (last && segment.start <= last.end) {
      last.end = Math.max(last.end, segment.end)
    } else {
      merged.push({ ...segment })
    }
  })
  return merged
}

function clipSegments(
  segments: GanttWorkingSegment[],
  from: number,
  to: number
): GanttWorkingSegment[] {
  return mergeSegments(
    segments
      .map((segment) => ({
        start: Math.max(segment.start, from),
        end: Math.min(segment.end, to)
      }))
      .filter((segment) => segment.end > segment.start)
  )
}

function overlapMs(segments: GanttWorkingSegment[], from: number, to: number): number {
  return segments.reduce(
    (total, segment) =>
      total + Math.max(0, Math.min(segment.end, to) - Math.max(segment.start, from)),
    0
  )
}

interface DayShiftResult {
  segments: GanttWorkingSegment[]
  appliedShiftLabels: string[]
  hasConfiguredShift: boolean
  makeupAssumption: boolean
}

/**
 * 计算某本地自然日的班次片段（含前一日跨夜班次落到本日的部分）。
 * MAKEUP 例外日：若当天星期不在班次 daysOfWeek 中，则沿用日历内全部班次时段，并在行状态中计数。
 */
function buildDayShiftSegments(
  calendar: Calendar,
  dayStart: number,
  makeup: boolean
): DayShiftResult {
  const dayOfWeek = isoDayOfWeek(dayStart)
  const previousDayOfWeek = dayOfWeek === 1 ? 7 : dayOfWeek - 1
  const segments: GanttWorkingSegment[] = []
  const labels = new Set<string>()
  let hasConfiguredShift = false

  const matchedToday = calendar.shifts.filter((shift) => shift.daysOfWeek.includes(dayOfWeek))
  const matchedYesterday = calendar.shifts.filter((shift) =>
    shift.daysOfWeek.includes(previousDayOfWeek)
  )

  let todayShifts = matchedToday
  let makeupAssumption = false
  if (makeup && matchedToday.length === 0) {
    const distinct = new Map<string, Shift>()
    calendar.shifts.forEach((shift) => {
      distinct.set(`${shift.startTime}-${shift.endTime}`, shift)
    })
    todayShifts = [...distinct.values()]
    makeupAssumption = todayShifts.length > 0
  }

  if (todayShifts.length > 0) {
    hasConfiguredShift = true
  }

  todayShifts.forEach((shift) => {
    const start = parseTimeToMinutes(shift.startTime)
    const end = parseTimeToMinutes(shift.endTime)
    if (start === null || end === null || start === end) {
      return
    }
    if (end > start) {
      segments.push({
        start: dayStart + start * MINUTE_MS,
        end: dayStart + end * MINUTE_MS
      })
    } else {
      // 跨夜班次：当日 start→24:00；00:00→end 部分由前一日的 carry 逻辑落到今天。
      segments.push({ start: dayStart + start * MINUTE_MS, end: dayStart + 24 * 60 * MINUTE_MS })
    }
    labels.add(`${shift.startTime}-${shift.endTime}`)
  })

  // 前一日跨夜班次（22:00→06:00）落到今天的 00:00-06:00 部分。
  matchedYesterday.forEach((shift) => {
    const start = parseTimeToMinutes(shift.startTime)
    const end = parseTimeToMinutes(shift.endTime)
    if (start === null || end === null || end >= start || end === 0) {
      return
    }
    segments.push({ start: dayStart, end: dayStart + end * MINUTE_MS })
    labels.add(`${shift.startTime}-${shift.endTime}`)
  })

  return {
    segments: clipSegments(segments, dayStart, addLocalDays(dayStart, 1)),
    appliedShiftLabels: [...labels],
    hasConfiguredShift,
    makeupAssumption
  }
}

function holidayLabel(holiday: Holiday): string {
  if (holiday.kind === 'LEGAL') {
    return `法定节假日：${holiday.name}`
  }
  if (holiday.kind === 'COMPANY') {
    return `公司停产日：${holiday.name}`
  }
  return `调休上班日：${holiday.name}`
}

/**
 * 把资源日历绑定折算为逐日工作片段 + 非工作窗口。
 * 未绑定 / 缺失 / 加载失败的行不产生任何热力数据，只返回显式状态。
 */
export function computeCalendarLayers(
  bindings: GanttCalendarBinding[],
  rowKeys: string[],
  fromMs: number,
  toMs: number
): GanttCalendarComputation {
  const bindingMap = new Map(bindings.map((binding) => [binding.resourceCode, binding]))
  const windows: GanttCalendarWindow[] = []
  const days: GanttCalendarDay[] = []
  const rowStates: GanttCalendarRowState[] = []
  let coveredDays = 0
  let uncoveredDays = 0
  let boundCount = 0
  let unboundCount = 0
  let missingCount = 0
  let errorCount = 0
  let heatComparableCount = 0

  rowKeys.forEach((rowKey) => {
    const binding = bindingMap.get(rowKey)
    const state: GanttCalendarBindingState = binding?.state ?? 'UNBOUND'
    if (!binding) {
      unboundCount += 1
      rowStates.push({
        rowKey,
        state: 'UNBOUND',
        calendarId: null,
        calendarName: null,
        message: 'C4 资源行未解析到日历绑定（未执行 /resources 查询）',
        coveredDays: 0,
        uncoveredDays: 0,
        makeupAssumptionDays: 0
      })
      return
    }
    if (state !== 'BOUND' || !binding.calendar) {
      if (state === 'UNBOUND') {
        unboundCount += 1
      } else if (state === 'MISSING') {
        missingCount += 1
      } else {
        errorCount += 1
      }
      rowStates.push({
        rowKey,
        state,
        calendarId: binding.calendarId,
        calendarName: binding.calendar?.name ?? null,
        message: binding.message,
        coveredDays: 0,
        uncoveredDays: 0,
        makeupAssumptionDays: 0
      })
      return
    }

    boundCount += 1
    const calendar = binding.calendar
    const holidayByDate = new Map<string, Holiday>()
    calendar.holidays?.forEach((holiday) => holidayByDate.set(holiday.date, holiday))
    const effectiveFrom = calendar.effectiveFrom
    const effectiveTo = calendar.effectiveTo ?? null

    let rowCovered = 0
    let rowUncovered = 0
    let makeupDays = 0
    let rowWorkingMinutes = 0

    let cursor = localDayStart(fromMs)
    while (cursor < toMs) {
      const dayStart = cursor
      const dayEnd = addLocalDays(dayStart, 1)
      cursor = dayEnd
      const effectiveStart = Math.max(dayStart, fromMs)
      const effectiveEnd = Math.min(dayEnd, toMs)
      if (effectiveEnd <= effectiveStart) {
        continue
      }
      const dateText = localDateString(dayStart)
      if (dateText < effectiveFrom || (effectiveTo !== null && dateText > effectiveTo)) {
        rowUncovered += 1
        uncoveredDays += 1
        continue
      }
      rowCovered += 1
      coveredDays += 1

      const holiday = holidayByDate.get(dateText)
      const nonWorkingHoliday = holiday && holiday.kind !== 'MAKEUP' ? holiday : null
      const makeup = holiday?.kind === 'MAKEUP'

      const dayEndIso = new Date(dayEnd).toISOString()
      if (nonWorkingHoliday) {
        windows.push({
          rowKey,
          startTime: new Date(effectiveStart).toISOString(),
          endTime: dayEndIso,
          kind: 'HOLIDAY',
          reason: holidayLabel(nonWorkingHoliday)
        })
        days.push({
          rowKey,
          dayStart: new Date(dayStart).toISOString(),
          dayEnd: dayEndIso,
          effectiveStart: new Date(effectiveStart).toISOString(),
          effectiveEnd: new Date(effectiveEnd).toISOString(),
          workingMinutes: 0,
          workingSegments: [],
          holidayName: nonWorkingHoliday.name,
          makeup: false,
          effective: true
        })
        continue
      }

      const shiftResult = buildDayShiftSegments(calendar, dayStart, makeup)
      if (shiftResult.makeupAssumption) {
        makeupDays += 1
      }
      const workingSegments = clipSegments(shiftResult.segments, effectiveStart, effectiveEnd)
      const workingMinutes = Math.round(
        overlapMs(workingSegments, effectiveStart, effectiveEnd) / MINUTE_MS
      )
      rowWorkingMinutes += workingMinutes

      days.push({
        rowKey,
        dayStart: new Date(dayStart).toISOString(),
        dayEnd: dayEndIso,
        effectiveStart: new Date(effectiveStart).toISOString(),
        effectiveEnd: new Date(effectiveEnd).toISOString(),
        workingMinutes,
        workingSegments,
        holidayName: makeup ? holiday?.name : undefined,
        makeup,
        effective: true
      })

      const shiftText = shiftResult.appliedShiftLabels.length
        ? `（班次 ${shiftResult.appliedShiftLabels.join('、')}）`
        : '（无匹配班次）'
      let windowCursor = effectiveStart
      workingSegments.forEach((segment) => {
        if (segment.start > windowCursor) {
          windows.push({
            rowKey,
            startTime: new Date(windowCursor).toISOString(),
            endTime: new Date(segment.start).toISOString(),
            kind: shiftResult.hasConfiguredShift ? 'OFF_SHIFT' : 'NON_WORKDAY',
            reason: shiftResult.hasConfiguredShift
              ? `非班次（休息）${shiftText}`
              : `非排班日（${weekdayLabel(isoDayOfWeek(dayStart))}）`
          })
        }
        windowCursor = Math.max(windowCursor, segment.end)
      })
      if (windowCursor < effectiveEnd) {
        windows.push({
          rowKey,
          startTime: new Date(windowCursor).toISOString(),
          endTime: new Date(effectiveEnd).toISOString(),
          kind: shiftResult.hasConfiguredShift ? 'OFF_SHIFT' : 'NON_WORKDAY',
          reason: shiftResult.hasConfiguredShift
            ? `非班次（休息）${shiftText}`
            : `非排班日（${weekdayLabel(isoDayOfWeek(dayStart))}）`
        })
      }
    }

    if (rowWorkingMinutes > 0) {
      heatComparableCount += 1
    }
    rowStates.push({
      rowKey,
      state: 'BOUND',
      calendarId: binding.calendarId,
      calendarName: calendar.name,
      message:
        rowUncovered > 0
          ? `日历 ${calendar.name} 未覆盖 ${rowUncovered} 天（effectiveFrom/To 之外）`
          : `已绑定日历 ${calendar.name}`,
      coveredDays: rowCovered,
      uncoveredDays: rowUncovered,
      makeupAssumptionDays: makeupDays
    })
  })

  return {
    windows,
    days,
    rowStates,
    summary: {
      boundCount,
      unboundCount,
      missingCount,
      errorCount,
      coveredDays,
      uncoveredDays,
      windowCount: windows.length,
      heatComparableCount,
      timezone: GANTT_CALENDAR_TIMEZONE
    }
  }
}

/** 计算任务占用时段与某日工作片段的交集分钟数（含重叠任务重复计入）。 */
export function workingOverlapMinutes(
  segments: GanttWorkingSegment[],
  fromMs: number,
  toMs: number
): number {
  return Math.round(overlapMs(segments, fromMs, toMs) / MINUTE_MS)
}

export const GANTT_DAY_MS = DAY_MS
