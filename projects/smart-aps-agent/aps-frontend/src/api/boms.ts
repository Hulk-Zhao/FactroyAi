/**
 * BOM 接口：路径 ID 按 contract-freeze C6 使用 UUID；
 * 列表按父件筛选为前端兼容性扩展参数（materials.yaml 仅定义 page/size/sort）。
 */
import http, { idempotencyHeaders, withPageQuery } from './http'
import type { PageQuery, PageResult } from '@/types/common'
import type { Bom, BomCreateRequest, BomQuery, BomTreeNode } from '@/types/boms'

export async function listBoms(query: PageQuery & BomQuery = {}): Promise<PageResult<Bom>> {
  const response = await http.get<PageResult<Bom>>('/boms', {
    params: withPageQuery(query, {
      parentMaterialCode: query.parentMaterialCode,
      status: query.status
    })
  })
  return response.data
}

export async function createBom(payload: BomCreateRequest, idempotencyKey?: string): Promise<Bom> {
  const response = await http.post<Bom>('/boms', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

export async function getBomTree(bomId: string): Promise<BomTreeNode> {
  const response = await http.get<BomTreeNode>(`/boms/${encodeURIComponent(bomId)}/tree`)
  return response.data
}

export async function activateBom(bomId: string, idempotencyKey?: string): Promise<Bom> {
  const response = await http.post<Bom>(`/boms/${encodeURIComponent(bomId)}/activate`, null, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}
