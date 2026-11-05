import http, { idempotencyHeaders } from './http'
import { getCalendar } from './calendars'
import { listResources } from './resources'
import type { Calendar } from '@/types/calendars'
import type {
  GanttCalendarBinding,
  OrderGanttData,
  OrderGanttQuery,
  PlanAction,
  ResourceCalendarResolution,
  ResourceGanttData,
  ResourceGanttQuery,
  TaskAdjustRequest,
  TaskAdjustResult
} from '@/types/gantt'
import type { Resource } from '@/types/resources'

const RESOURCE_PAGE_SIZE = 200
const RESOURCE_MAX_PAGES = 10

function describeError(exception: unknown): string {
  return exception instanceof Error ? exception.message : String(exception)
}

/**
 * 资源行 → 工作日历补齐（C4 不含 calendarId，需要 C14 的两个端点）：
 * 1. GET /resources 分页查找各 resourceCode 的 calendarId；
 * 2. GET /calendars/{id} 拉取班次/例外日。
 *
 * 缺口处理：资源不存在 → MISSING；calendarId 为空 → UNBOUND；详情失败 → ERROR；
 * 资源列表整体失败 → 全部 ERROR 且 resourceListLoaded=false。
 * 任何情况都不使用 24h 默认分母，避免静默把推定值当成负载。
 */
export async function resolveResourceCalendars(
  resourceCodes: string[]
): Promise<ResourceCalendarResolution> {
  const codes = [...new Set(resourceCodes.filter((code) => code.length > 0))]
  if (codes.length === 0) {
    return { bindings: [], issues: [], resourceListLoaded: true }
  }

  const resources = new Map<string, Resource>()
  try {
    for (let page = 1; page <= RESOURCE_MAX_PAGES; page += 1) {
      const result = await listResources({ page, size: RESOURCE_PAGE_SIZE })
      result.items.forEach((resource) => resources.set(resource.code, resource))
      const missing = codes.filter((code) => !resources.has(code))
      if (missing.length === 0 || page >= result.totalPages) {
        break
      }
    }
  } catch (exception) {
    const message = describeError(exception)
    return {
      bindings: codes.map((code) => ({
        resourceCode: code,
        calendarId: null,
        state: 'ERROR',
        calendar: null,
        message: `资源列表加载失败：${message}`
      })),
      issues: [`GET /resources 不可用，无法解析资源日历：${message}`],
      resourceListLoaded: false
    }
  }

  const calendarIds = new Map<string, string>()
  resources.forEach((resource) => {
    if (codes.includes(resource.code) && resource.calendarId) {
      calendarIds.set(resource.code, resource.calendarId)
    }
  })

  const uniqueCalendarIds = [...new Set(calendarIds.values())]
  const calendars = new Map<string, Calendar>()
  const failedCalendars = new Map<string, string>()
  await Promise.all(
    uniqueCalendarIds.map(async (calendarId) => {
      try {
        calendars.set(calendarId, await getCalendar(calendarId))
      } catch (exception) {
        failedCalendars.set(calendarId, describeError(exception))
      }
    })
  )

  const issues = new Set<string>()
  const bindings: GanttCalendarBinding[] = codes.map((code) => {
    const resource = resources.get(code)
    if (!resource) {
      issues.add(`GET /resources 未返回资源 ${code}（可能跨沙箱或已删除）`)
      return {
        resourceCode: code,
        calendarId: null,
        state: 'MISSING',
        calendar: null,
        message: '资源列表未返回该资源编码；停机与热力不可比'
      }
    }
    const calendarId = resource.calendarId ?? null
    if (!calendarId) {
      issues.add(`资源 ${code} 未绑定 calendarId（C14 响应字段为空）`)
      return {
        resourceCode: code,
        calendarId: null,
        state: 'UNBOUND',
        calendar: null,
        message: '资源未绑定工作日历；停机与热力不可比'
      }
    }
    const failed = failedCalendars.get(calendarId)
    if (failed) {
      issues.add(`GET /calendars/{id} 加载失败（${calendarId}）：${failed}`)
      return {
        resourceCode: code,
        calendarId,
        state: 'ERROR',
        calendar: null,
        message: `日历详情加载失败：${failed}；停机与热力不可比`
      }
    }
    const calendar = calendars.get(calendarId)
    if (!calendar) {
      issues.add(`日历 ${calendarId} 不在已加载结果中`)
      return {
        resourceCode: code,
        calendarId,
        state: 'MISSING',
        calendar: null,
        message: '日历详情缺失；停机与热力不可比'
      }
    }
    return {
      resourceCode: code,
      calendarId,
      state: 'BOUND',
      calendar,
      message: `已绑定日历 ${calendar.name}`
    }
  })

  return { bindings, issues: [...issues], resourceListLoaded: true }
}

export async function getResourceGantt(query: ResourceGanttQuery): Promise<ResourceGanttData> {
  const response = await http.get<ResourceGanttData>('/gantt/resource', {
    params: {
      sandboxId: query.sandboxId,
      from: query.from,
      to: query.to,
      resourceCodes: query.resourceCodes
    }
  })
  return response.data
}

export async function getOrderGantt(query: OrderGanttQuery): Promise<OrderGanttData> {
  const response = await http.get<OrderGanttData>('/gantt/order', {
    params: {
      sandboxId: query.sandboxId,
      from: query.from,
      to: query.to,
      orderNos: query.orderNos
    }
  })
  return response.data
}

/** C22 人工调整：dryRun=true 时后端只校验不落库（applied=true 但 adjustmentId=null）。 */
export async function adjustTask(
  taskId: number,
  payload: TaskAdjustRequest,
  idempotencyKey?: string
): Promise<TaskAdjustResult> {
  const response = await http.post<TaskAdjustResult>(`/tasks/${taskId}/adjust`, payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

/** C22 撤销：只允许撤销该任务栈最近一次调整；撤销后再调整会产生新记录。 */
export async function undoAdjustment(
  adjustmentId: number,
  idempotencyKey?: string
): Promise<TaskAdjustResult> {
  const response = await http.post<TaskAdjustResult>(
    `/tasks/adjustments/${adjustmentId}/undo`,
    null,
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}

/** C22 重做：若该调整之后已有新调整，后端返回 40901。 */
export async function redoAdjustment(
  adjustmentId: number,
  idempotencyKey?: string
): Promise<TaskAdjustResult> {
  const response = await http.post<TaskAdjustResult>(
    `/tasks/adjustments/${adjustmentId}/redo`,
    null,
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}

export async function publishPlan(planId: string, idempotencyKey?: string): Promise<PlanAction> {
  const response = await http.post<PlanAction>(
    `/plans/${encodeURIComponent(planId)}/publish`,
    null,
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}

export async function rollbackPlan(planId: string, idempotencyKey?: string): Promise<PlanAction> {
  const response = await http.post<PlanAction>(
    `/plans/${encodeURIComponent(planId)}/rollback`,
    null,
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}
