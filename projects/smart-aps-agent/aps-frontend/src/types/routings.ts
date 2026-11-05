/**
 * 工艺路线（DAG）域类型。
 * 对齐 docs/api/contract-freeze.md C15 与 materials.yaml RoutingNode/RoutingEdge/RoutingGraph：
 * - 资源引用统一使用 UUID：primaryResourceId / secondaryResourceIds；
 *   primaryResourceCode 为只读响应回显，仅用于展示。
 * - 边按 seqNo 表达：fromSeqNo / toSeqNo，服务端映射为 node UUID。
 * - 保存使用 PUT /routings/{id}/graph，version 为必填乐观锁。
 * id/position/operationCode/operationName 为前端编辑所需的附加字段，
 * 契约未禁止扩展字段，后端可忽略 position。
 */

export type RoutingStatus = 'DRAFT' | 'ACTIVE' | 'INACTIVE'

export type DependType = 'FS' | 'SS' | 'FF'

export type ResourceType = 'PRIMARY' | 'SECONDARY' | 'HUMAN' | 'TOOL'

/**
 * 节点主资源三态（C15 §20.3.1.1）：
 * INHERIT 不覆盖、沿用工序模板主资源；FLOAT 显式浮动、不指定资源；FIXED 固定到指定资源。
 * 必须与「primaryResourceId 为空」区分开：INHERIT 回退模板，FLOAT 明确跳过回退。
 */
export type PrimaryResourceMode = 'INHERIT' | 'FLOAT' | 'FIXED'

/**
 * 节点辅助资源两态（C15 §20.3.1.5）：
 * INHERIT 缺键回退模板；EXPLICIT 下发数组（[] 表示显式不使用辅助资源）。
 */
export type SecondaryResourceMode = 'INHERIT' | 'EXPLICIT'

/** 工序模版（GET /operation-templates，分页）。 */
export interface OperationTemplate {
  id: string
  code: string
  name: string
  templateVersion: number
  primaryResourceId: string | null
  /** 后端 OperationTemplateView 未返回资源组，保留可选以兼容契约扩展。 */
  primaryResourceGroupId?: string | null
  secondaryResourceIds: string[]
  inputMaterialIds?: string[]
  /** 后端未返回产出物料，保留可选。 */
  outputMaterialId?: string | null
  standardSeconds: number
  setupSeconds: number
  teardownSeconds: number
  version: number
}

export interface OperationTemplateQuery {
  code?: string
  name?: string
  resourceType?: ResourceType
}

export interface RoutingNodePosition {
  x: number
  y: number
}

/** 工序节点；id/position 为前端编辑附加字段，其余字段对齐 RoutingNode。 */
export interface RoutingNodePayload {
  seqNo: number
  operationTemplateId: string | null
  operationCode: string
  operationName: string
  /** 后端可能返回 null（种子数据未维护标准工时）。 */
  standardSeconds: number | null
  /** 后端可能返回 null（种子数据未维护换型工时）。 */
  setupSeconds: number | null
  primaryResourceId: string | null
  /** C15 §20.3.1.1 三态；缺省按 INHERIT 处理，GET 响应会回显。 */
  primaryResourceMode?: PrimaryResourceMode
  /** 只读响应回显，由 primaryResourceId 反查资源；请求不发送。 */
  primaryResourceCode?: string | null
  /** C15 §20.3.1.5：缺键=回退模板，[]=显式不使用辅助资源。 */
  secondaryResourceIds?: string[]
  optional: boolean
}

export interface RoutingNode extends Omit<RoutingNodePayload, 'standardSeconds' | 'setupSeconds'> {
  standardSeconds: number
  setupSeconds: number
  id: string
  position: RoutingNodePosition
  primaryResourceCode: string | null
  /** 编辑器内部状态；载入时由响应回显规范化，保存时写回契约字段。 */
  primaryResourceMode?: PrimaryResourceMode
  secondaryResourceMode?: SecondaryResourceMode
  secondaryResourceIds?: string[]
}

/** 依赖边；id 为前端选中附加字段，端点按 seqNo 表达。 */
export interface RoutingEdgePayload {
  fromSeqNo: number
  toSeqNo: number
  dependType: DependType
  lagMinutes: number
}

export interface RoutingEdge extends RoutingEdgePayload {
  id: string
}

export interface Routing {
  id: string
  productCode: string
  /** 后端 RoutingView 未返回产品名称，由前端在需要时自行补全。 */
  productName?: string
  routingVersion: string
  effectiveDate: string
  expiryDate?: string | null
  status: RoutingStatus
  nodes: RoutingNode[]
  edges: RoutingEdge[]
  version: number
  /** 后端列表/详情均未返回更新时间（排序参数读库列）。 */
  updatedAt?: string
}

/**
 * GET/PUT /routings/{id}/graph 响应。
 * 注意：真实后端图端点只返回 routingId/nodes/edges，不含状态与乐观锁版本；
 * 编辑器需另取 GET /routings/{id} 详情（后端补充端点）获得 status/version。
 * 其余字段为可选，避免把缺失字段误判为有值。
 */
export interface RoutingGraphResponse {
  routingId: string
  nodes: RoutingNodePayload[]
  edges: RoutingEdgePayload[]
  productCode?: string
  productName?: string
  routingVersion?: string
  effectiveDate?: string
  expiryDate?: string | null
  status?: RoutingStatus
  version?: number
  updatedAt?: string
}

export interface RoutingGraph extends Routing {
  routingId: string
}

export interface RoutingQuery {
  productCode?: string
  /** 业务版本号，对应 contract 的 version 参数。 */
  version?: string
  status?: RoutingStatus
}

export interface RoutingCreateRequest {
  productCode: string
  routingVersion: string
  effectiveDate: string
  expiryDate?: string | null
  nodes: RoutingNodePayload[]
  edges: RoutingEdgePayload[]
}

export interface RoutingGraphUpdateRequest {
  nodes: RoutingNodePayload[]
  edges: RoutingEdgePayload[]
  /** 乐观锁版本；与服务端不一致时返回 40901。 */
  version: number
}
