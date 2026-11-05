import http, { idempotencyHeaders, withPageQuery } from './http'
import type { PageQuery, PageResult } from '@/types/common'
import type {
  AvailableTime,
  Calendar,
  CalendarCreateRequest,
  Material,
  MaterialCreateRequest,
  MaterialQuery,
  MaterialUpdateRequest,
  MaintenanceRequest,
  Resource,
  ResourceCreateRequest,
  Routing,
  RoutingCreateRequest,
  RoutingGraph,
  ImportResult
} from '@/types/materials'

export async function listMaterials(
  query: PageQuery & MaterialQuery = {}
): Promise<PageResult<Material>> {
  const response = await http.get<PageResult<Material>>('/materials', {
    params: withPageQuery(query, {
      type: query.type,
      code: query.code,
      name: query.name
    })
  })
  return response.data
}

export async function createMaterial(
  payload: MaterialCreateRequest,
  idempotencyKey?: string
): Promise<Material> {
  const response = await http.post<Material>('/materials', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

export async function getMaterial(code: string): Promise<Material> {
  const response = await http.get<Material>(`/materials/${encodeURIComponent(code)}`)
  return response.data
}

export async function updateMaterial(
  code: string,
  payload: MaterialUpdateRequest,
  idempotencyKey?: string
): Promise<Material> {
  const response = await http.put<Material>(`/materials/${encodeURIComponent(code)}`, payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

export async function deleteMaterial(code: string, idempotencyKey?: string): Promise<void> {
  await http.delete(`/materials/${encodeURIComponent(code)}`, {
    headers: idempotencyHeaders(idempotencyKey)
  })
}

export async function importMaterials(file: File): Promise<ImportResult> {
  const formData = new FormData()
  formData.append('file', file)
  const response = await http.post<ImportResult>('/materials/import', formData)
  return response.data
}

export async function listResources(query: PageQuery = {}): Promise<PageResult<Resource>> {
  const response = await http.get<PageResult<Resource>>('/resources', {
    params: withPageQuery(query)
  })
  return response.data
}

export async function createResource(
  payload: ResourceCreateRequest,
  idempotencyKey?: string
): Promise<Resource> {
  const response = await http.post<Resource>('/resources', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

export async function maintainResource(
  code: string,
  payload: MaintenanceRequest,
  idempotencyKey?: string
): Promise<Resource> {
  const response = await http.post<Resource>(
    `/resources/${encodeURIComponent(code)}/maintenance`,
    payload,
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}

export async function listCalendars(query: PageQuery = {}): Promise<PageResult<Calendar>> {
  const response = await http.get<PageResult<Calendar>>('/calendars', {
    params: withPageQuery(query)
  })
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

export async function getAvailableWorkingTime(
  calendarId: string,
  from: string,
  to: string
): Promise<AvailableTime> {
  const response = await http.get<AvailableTime>(
    `/calendars/${encodeURIComponent(calendarId)}/available`,
    { params: { from, to } }
  )
  return response.data
}

export async function listRoutings(query: PageQuery = {}): Promise<PageResult<Routing>> {
  const response = await http.get<PageResult<Routing>>('/routings', {
    params: withPageQuery(query)
  })
  return response.data
}

export async function createRouting(
  payload: RoutingCreateRequest,
  idempotencyKey?: string
): Promise<Routing> {
  const response = await http.post<Routing>('/routings', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

export async function getRoutingGraph(routingId: string): Promise<RoutingGraph> {
  const response = await http.get<RoutingGraph>(`/routings/${encodeURIComponent(routingId)}/graph`)
  return response.data
}
