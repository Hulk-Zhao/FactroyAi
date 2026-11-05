import http, { idempotencyHeaders, withPageQuery } from './http'
import type { PageQuery, PageResult } from '@/types/common'
import type {
  OperationTemplate,
  OperationTemplateQuery,
  Routing,
  RoutingCreateRequest,
  RoutingEdge,
  RoutingEdgePayload,
  RoutingGraphResponse,
  RoutingGraphUpdateRequest,
  RoutingNode,
  RoutingNodePayload,
  RoutingQuery
} from '@/types/routings'

/** 把编辑器节点裁剪为 C15 契约字段（去掉前端 id/position）。 */
export function toRoutingNodePayload(node: RoutingNode): RoutingNodePayload {
  const primaryResourceMode = node.primaryResourceMode ?? 'INHERIT'
  const payload: RoutingNodePayload = {
    seqNo: node.seqNo,
    operationTemplateId: node.operationTemplateId,
    operationCode: node.operationCode,
    operationName: node.operationName,
    standardSeconds: node.standardSeconds,
    setupSeconds: node.setupSeconds,
    // FLOAT/INHERIT 均不指定资源；FIXED 必须携带资源 UUID。
    primaryResourceId: primaryResourceMode === 'FIXED' ? node.primaryResourceId : null,
    primaryResourceMode,
    optional: node.optional
  }
  // C15 §20.3.1.5：INHERIT 缺键回退模板；EXPLICIT 才下发数组（含空数组）。
  if ((node.secondaryResourceMode ?? 'INHERIT') === 'EXPLICIT') {
    payload.secondaryResourceIds = [...(node.secondaryResourceIds ?? [])]
  }
  return payload
}

/** 把编辑器边裁剪为 C15 契约字段（fromSeqNo/toSeqNo）。 */
export function toRoutingEdgePayload(edge: RoutingEdge): RoutingEdgePayload {
  return {
    fromSeqNo: edge.fromSeqNo,
    toSeqNo: edge.toSeqNo,
    dependType: edge.dependType,
    lagMinutes: edge.lagMinutes
  }
}

/**
 * 工艺路线列表（C15）：productCode 精确、version 业务版本号、status 精确，条件之间 AND。
 * 路径参数使用 UUID（C6）。
 */
export async function listRoutings(
  query: PageQuery & RoutingQuery = {}
): Promise<PageResult<Routing>> {
  const response = await http.get<PageResult<Routing>>('/routings', {
    params: withPageQuery(query, {
      productCode: query.productCode,
      version: query.version,
      status: query.status
    })
  })
  return response.data
}

/** 新建工艺路线版本（DRAFT），nodes 至少 1 个。 */
export async function createRouting(
  payload: RoutingCreateRequest,
  idempotencyKey?: string
): Promise<Routing> {
  const response = await http.post<Routing>('/routings', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

/** 读取工艺路线 DAG（节点 + 边，边按 seqNo 表达）。 */
export async function getRoutingGraph(routingId: string): Promise<RoutingGraphResponse> {
  const response = await http.get<RoutingGraphResponse>(
    `/routings/${encodeURIComponent(routingId)}/graph`
  )
  return response.data
}

/**
 * 工艺路线详情（后端 M1-BD-07 补充端点，契约外）。
 * 图端点不返回 status/version，编辑器用它读取乐观锁版本与只读状态。
 */
export async function getRouting(routingId: string): Promise<Routing> {
  const response = await http.get<Routing>(`/routings/${encodeURIComponent(routingId)}`)
  return response.data
}

/** 整体替换 DAG；version 必填乐观锁，ACTIVE/INACTIVE 返回 42200，版本冲突 40901。 */
export async function saveRoutingGraph(
  routingId: string,
  payload: RoutingGraphUpdateRequest,
  idempotencyKey?: string
): Promise<RoutingGraphResponse> {
  const response = await http.put<RoutingGraphResponse>(
    `/routings/${encodeURIComponent(routingId)}/graph`,
    payload,
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}

/**
 * 工序模版分页目录（C15）：code/name 模糊 OR，resourceType 精确 AND。
 */
export async function listOperationTemplates(
  query: PageQuery & OperationTemplateQuery = {}
): Promise<PageResult<OperationTemplate>> {
  const response = await http.get<PageResult<OperationTemplate>>('/operation-templates', {
    params: withPageQuery(query, {
      code: query.code,
      name: query.name,
      resourceType: query.resourceType
    })
  })
  return response.data
}

/** 工序模版详情（C16：统一 {id} UUID 寻址，禁止 {code} 详情路径）。 */
export async function getOperationTemplate(id: string): Promise<OperationTemplate> {
  const response = await http.get<OperationTemplate>(
    `/operation-templates/${encodeURIComponent(id)}`
  )
  return response.data
}
