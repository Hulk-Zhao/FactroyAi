import http, { idempotencyHeaders, withPageQuery } from './http'
import type { PageQuery, PageResult } from '@/types/common'
import type {
  Resource,
  ResourceCreateRequest,
  ResourceQuery,
  ResourceUpdateRequest
} from '@/types/resources'

export async function listResources(
  query: PageQuery & ResourceQuery = {}
): Promise<PageResult<Resource>> {
  const response = await http.get<PageResult<Resource>>('/resources', {
    params: withPageQuery(query, {
      type: query.type,
      code: query.code,
      name: query.name,
      groupId: query.groupId
    })
  })
  return response.data
}

/** 详情端点属契约缺口（yaml 未定义），需 S0 补 yaml。 */
export async function getResource(code: string): Promise<Resource> {
  const response = await http.get<Resource>(`/resources/${encodeURIComponent(code)}`)
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

/** 更新端点属契约缺口（yaml 未定义），需 S0 补 yaml；路径参数按 C6 使用 code。 */
export async function updateResource(
  code: string,
  payload: ResourceUpdateRequest,
  idempotencyKey?: string
): Promise<Resource> {
  const response = await http.put<Resource>(`/resources/${encodeURIComponent(code)}`, payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

/** 停用端点属契约缺口（yaml 未定义），语义为软删除；需 S0 补 yaml。 */
export async function deactivateResource(code: string, idempotencyKey?: string): Promise<void> {
  await http.delete(`/resources/${encodeURIComponent(code)}`, {
    headers: idempotencyHeaders(idempotencyKey)
  })
}
