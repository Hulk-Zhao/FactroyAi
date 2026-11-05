import http, { idempotencyHeaders, withPageQuery } from './http'
import type { PageQuery, PageResult } from '@/types/common'
import type {
  Calendar,
  CalendarCreateRequest,
  CalendarQuery,
  CalendarUpdateRequest
} from '@/types/calendars'

export async function listCalendars(
  query: PageQuery & CalendarQuery = {}
): Promise<PageResult<Calendar>> {
  const response = await http.get<PageResult<Calendar>>('/calendars', {
    params: withPageQuery(query, {
      name: query.name,
      type: query.type
    })
  })
  return response.data
}

/** 详情端点属契约缺口（yaml 未定义），需 S0 补 yaml；路径参数按 C6 使用 UUID。 */
export async function getCalendar(id: string): Promise<Calendar> {
  const response = await http.get<Calendar>(`/calendars/${encodeURIComponent(id)}`)
  return response.data
}

export async function createCalendar(
  payload: CalendarCreateRequest,
  idempotencyKey?: string
): Promise<Calendar> {
  const response = await http.post<Calendar>('/calendars', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

/** 更新端点属契约缺口；历史日历服务端返回 403（前端会先拦截）。 */
export async function updateCalendar(
  id: string,
  payload: CalendarUpdateRequest,
  idempotencyKey?: string
): Promise<Calendar> {
  const response = await http.put<Calendar>(`/calendars/${encodeURIComponent(id)}`, payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}
