/**
 * 日历生效态推导：历史日历只读（schema.md 4.5 / 契约日历版本规则）。
 * 历史 = effective_to 早于今天；待生效 = effective_from 晚于今天；其余为生效中。
 */
import type { Calendar, CalendarLifecycle } from '@/types/calendars'

/** 本地时区当天 yyyy-MM-dd（避免 toISOString 的 UTC 偏移导致误判）。 */
export function today(): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function calendarLifecycle(calendar: Calendar, referenceDate = today()): CalendarLifecycle {
  if (calendar.effectiveTo && calendar.effectiveTo < referenceDate) {
    return 'HISTORICAL'
  }
  if (calendar.effectiveFrom > referenceDate) {
    return 'FUTURE'
  }
  return 'EFFECTIVE'
}

export function lifecycleLabel(lifecycle: CalendarLifecycle): string {
  switch (lifecycle) {
    case 'HISTORICAL':
      return '历史（只读）'
    case 'FUTURE':
      return '待生效'
    default:
      return '生效中'
  }
}

export function lifecycleTagType(lifecycle: CalendarLifecycle): 'info' | 'warning' | 'success' {
  switch (lifecycle) {
    case 'HISTORICAL':
      return 'info'
    case 'FUTURE':
      return 'warning'
    default:
      return 'success'
  }
}

/** 历史日历不可改，只能复制为新版本；契约 C14 版本号为整数，复制时 +1。 */
export function nextCalendarVersion(version: number): number {
  return Number.isFinite(version) ? Number(version) + 1 : 1
}
