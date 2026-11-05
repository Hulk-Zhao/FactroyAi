/**
 * calendars 域类型，字段对齐 docs/api/openapi/materials.yaml 的 Calendar 段落；
 * 枚举取值对齐 docs/db/schema.md 4.1 节 CHECK 取值（calendar.type / holidays[].kind）。
 *
 * 契约缺口（详见交付汇报）：
 * - yaml 仅定义 GET/POST /calendars 与 GET /calendars/{id}/available，
 *   未定义 GET/PUT/DELETE /calendars/{id}；实现按 REST 惯例提供，需 S0 补 yaml。
 * - 契约 C14 已明确 calendar_version 为 INTEGER；后端真实响应也返回数字，前端按数字使用。
 * - 历史日历只读由服务端返回 403/40300，属前端与后端共同约定的业务规则。
 */
export type CalendarType = 'FACTORY' | 'RESOURCE' | 'SHIFT'

export type HolidayKind = 'LEGAL' | 'COMPANY' | 'MAKEUP'

/** 班次：daysOfWeek 取 1-7（周一=1）。endTime 小于 startTime 表示跨零点。 */
export interface Shift {
  startTime: string
  endTime: string
  daysOfWeek: number[]
}

export interface Holiday {
  date: string
  name: string
  kind: HolidayKind
}

export interface Calendar {
  id: string
  name: string
  /** 契约 C14：calendarVersion 为 INTEGER（schema 中为 INTEGER，后端按数字返回）。 */
  calendarVersion: number
  type: CalendarType
  resourceId?: string | null
  resourceGroupId?: string | null
  effectiveFrom: string
  effectiveTo?: string | null
  shifts: Shift[]
  holidays?: Holiday[]
  version: number
  createdAt?: string
  updatedAt?: string
}

export interface CalendarCreateRequest {
  name: string
  /** 契约 C14：整数版本号（1, 2, ...），非 "V1.0" 字符串。 */
  calendarVersion: number
  type: CalendarType
  resourceId?: string | null
  resourceGroupId?: string | null
  effectiveFrom: string
  effectiveTo?: string | null
  shifts: Shift[]
  holidays?: Holiday[]
}

export interface CalendarUpdateRequest extends CalendarCreateRequest {
  version: number
}

export interface CalendarQuery {
  /** 名称模糊筛选；yaml 未列，属前端兼容性扩展。 */
  name?: string
  type?: CalendarType
}

/** 日历生效态：由 effectiveFrom/effectiveTo 与当天日期推导，用于前端只读拦截。 */
export type CalendarLifecycle = 'FUTURE' | 'EFFECTIVE' | 'HISTORICAL'
