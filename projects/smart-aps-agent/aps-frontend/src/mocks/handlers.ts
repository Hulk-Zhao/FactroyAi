/**
 * 契约 Mock 处理器：按 OpenAPI 路径返回统一 Result 信封。
 * 仅用于前端并行开发；通过 VITE_ENABLE_MOCK=true 启用。
 */
import type { InternalAxiosRequestConfig } from 'axios'

import { DEFAULT_SANDBOX_ID } from '@/constants/defaults'
import {
  IMPORT_ENTITY_OPTIONS,
  IMPORT_MAX_FILE_BYTES,
  findImportEntity
} from '@/constants/import'
import type { ImportError, ImportJobStatus, ImportJobView, ImportResult } from '@/types/import'
import type { PageResult } from '@/types/common'
import type { Bom, BomCreateRequest, BomTreeNode } from '@/types/boms'
import type { Material, MaterialCreateRequest } from '@/types/materials'
import type { MrpAnalysisParams, MrpAnalysisResult } from '@/types/mrp'
import type { Calendar, CalendarCreateRequest } from '@/types/calendars'
import type { Resource, ResourceCreateRequest } from '@/types/resources'
import type {
  DependType,
  Routing,
  RoutingCreateRequest,
  RoutingEdge,
  RoutingEdgePayload,
  RoutingGraphUpdateRequest,
  RoutingNode,
  RoutingNodePayload
} from '@/types/routings'
import type {
  Order,
  OrderCreateRequest,
  OrderFreezeRequest,
  OrderUpdateRequest,
  Sandbox,
  SandboxApplyResult,
  SandboxCompare,
  SandboxCreateRequest,
  Strategy,
  StrategyCreateRequest,
  StrategyDetail,
  StrategyRule,
  StrategyUpdateRequest
} from '@/types/orders'
import type {
  AuditLog,
  ConfigUpdateRequest,
  LoginRequest,
  MeView,
  PermissionCode,
  RoleCreateRequest,
  RoleView,
  User,
  UserCreateRequest,
  UserUpdateRequest
} from '@/types/system'
import type { SolveSubmitRequest, SolveTaskDetail } from '@/types/solve'
import type { TaskAdjustRequest } from '@/types/gantt'
import type {
  PlanAction,
  PlanCompareItem,
  PlanCompareResult,
  PlanDetail,
  PlanPublishRequest,
  PlanRollbackRequest
} from '@/types/plans'

import {
  auditLogs,
  availableTime,
  boms,
  calendars,
  configs,
  ganttOrderRows,
  ganttResourceRows,
  ganttTasks,
  materials,
  mrpAnalysisFixture,
  operationTemplates,
  orders,
  permissions,
  resources,
  roles,
  routings,
  sandboxes,
  solveTaskDetails,
  strategies,
  users
} from './data'

export interface MockReply {
  status: number
  /** JSON 信封、裸对象（C39 JobView）或二进制 Blob 均可。 */
  body: unknown
  headers?: Record<string, string>
}

function traceOf(config: InternalAxiosRequestConfig): string {
  const header = config.headers?.get?.('X-Trace-Id')
  if (typeof header === 'string' && header.length > 0) {
    return header
  }
  return `mock-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
}

function ok<T>(config: InternalAxiosRequestConfig, data: T, status = 200): MockReply {
  return {
    status,
    body: { code: 0, message: 'success', data, traceId: traceOf(config) }
  }
}

function fail(
  config: InternalAxiosRequestConfig,
  code: number,
  message: string,
  status: number
): MockReply {
  return {
    status,
    body: { code, message, data: null, traceId: traceOf(config) }
  }
}

function resolvePath(config: InternalAxiosRequestConfig): string {
  const raw = config.url ?? ''
  const withoutHost = raw.replace(/^https?:\/\/[^/]+/i, '')
  const withoutBase = withoutHost.replace(/^\/api\/v1/, '')
  const path = withoutBase.split('?')[0] || '/'
  return path.length > 1 ? path.replace(/\/+$/, '') : path
}

function readParams(config: InternalAxiosRequestConfig): Record<string, unknown> {
  return (config.params ?? {}) as Record<string, unknown>
}

function readBody<T>(config: InternalAxiosRequestConfig): T {
  const raw = config.data
  if (typeof raw === 'string') {
    try {
      return JSON.parse(raw) as T
    } catch {
      return {} as T
    }
  }
  if (
    raw &&
    typeof raw === 'object' &&
    !(typeof FormData !== 'undefined' && raw instanceof FormData)
  ) {
    return raw as T
  }
  return {} as T
}

function paginate<T>(items: T[], params: Record<string, unknown>): PageResult<T> {
  const page = Math.max(1, Number(params.page) || 1)
  const size = Math.min(Math.max(Number(params.size) || 20, 1), 200)
  const total = items.length
  return {
    items: items.slice((page - 1) * size, page * size),
    page,
    size,
    total,
    totalPages: Math.ceil(total / size)
  }
}

function listParam(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map((item) => String(item))
  }
  if (typeof value === 'string' && value.length > 0) {
    return value.split(',')
  }
  return []
}

function uuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () =>
    Math.floor(Math.random() * 16).toString(16)
  )
}

function now(): string {
  return new Date().toISOString()
}

function nextNumericId(): number {
  return solveTaskDetails.reduce((max, item) => Math.max(max, item.id), 1000) + 1
}

const BOM_TREE_MAX_DEPTH = 5

function materialNameOf(code: string): string {
  return materials.find((item) => item.code === code)?.name ?? code
}

/**
 * 按父件逐层拼接 BOM 树；优先使用 ACTIVE 子 BOM，最多展开 5 层并阻止环引用。
 */
function buildBomTree(bomId: string): BomTreeNode | null {
  const rootBom = boms.find((item) => item.id === bomId)
  if (!rootBom) {
    return null
  }
  const visiting = new Set<string>()

  function build(bom: Bom, level: number, qtyPerUnit: number): BomTreeNode {
    visiting.add(bom.parentMaterialCode)
    const children: BomTreeNode[] = []
    if (level < BOM_TREE_MAX_DEPTH) {
      bom.items.forEach((item) => {
        if (visiting.has(item.materialCode)) {
          return
        }
        const childBom =
          boms.find(
            (candidate) =>
              candidate.parentMaterialCode === item.materialCode && candidate.status === 'ACTIVE'
          ) ?? boms.find((candidate) => candidate.parentMaterialCode === item.materialCode)
        if (childBom) {
          children.push(build(childBom, level + 1, item.qtyPerUnit))
          return
        }
        children.push({
          bomId: '',
          parentMaterialCode: item.materialCode,
          materialName: materialNameOf(item.materialCode),
          qtyPerUnit: item.qtyPerUnit,
          level: level + 1,
          children: []
        })
      })
    }
    visiting.delete(bom.parentMaterialCode)
    return {
      bomId: bom.id,
      parentMaterialCode: bom.parentMaterialCode,
      materialName: materialNameOf(bom.parentMaterialCode),
      qtyPerUnit,
      level,
      children
    }
  }

  return build(rootBom, 1, 1)
}

const DEPEND_TYPES: DependType[] = ['FS', 'SS', 'FF']

/** Mock 侧保存校验：复刻服务端按 seqNo 的环检测/唯一键/必填约束，避免前端错误被放行。 */
function validateRoutingPayload(
  nodes: RoutingNodePayload[],
  edges: RoutingEdgePayload[]
): string | null {
  if (nodes.length === 0) {
    return '工艺路线至少需要一个工序节点'
  }

  const seqNos = new Set<number>()
  for (const node of nodes) {
    if (!Number.isInteger(node.seqNo) || node.seqNo < 0) {
      return '工序 seqNo 必须为非负整数'
    }
    if (seqNos.has(node.seqNo)) {
      return `seqNo=${node.seqNo} 重复，请为每个工序设置唯一序号`
    }
    seqNos.add(node.seqNo)
  }

  const templateIds = new Set(operationTemplates.map((template) => template.id))
  const missingTemplate = nodes.find(
    (node) => !node.operationTemplateId || !templateIds.has(node.operationTemplateId)
  )
  if (missingTemplate) {
    return `工序「${missingTemplate.seqNo} · ${missingTemplate.operationName || missingTemplate.operationCode}」缺少有效工序模版`
  }

  const resourceIds = new Set(resources.map((resource) => resource.id))
  for (const node of nodes) {
    const primaryResourceMode = node.primaryResourceMode ?? 'INHERIT'
    if (!['INHERIT', 'FLOAT', 'FIXED'].includes(primaryResourceMode)) {
      return `工序「${node.seqNo} · ${node.operationName}」的主资源模式不合法`
    }
    if (primaryResourceMode === 'FIXED' && !node.primaryResourceId) {
      return `工序「${node.seqNo} · ${node.operationName}」主资源模式为 FIXED 但未选择资源`
    }
    if (
      primaryResourceMode === 'FIXED' &&
      node.primaryResourceId &&
      !resourceIds.has(node.primaryResourceId)
    ) {
      return `工序「${node.seqNo} · ${node.operationName}」的主资源不存在`
    }
    const secondaryResourceIds = node.secondaryResourceIds ?? []
    const missingSecondary = secondaryResourceIds.find((resourceId) => !resourceIds.has(resourceId))
    if (missingSecondary) {
      return `工序「${node.seqNo} · ${node.operationName}」的副资源 ${missingSecondary} 不存在`
    }
  }

  const edgeKeys = new Set<string>()
  for (const edge of edges) {
    if (!seqNos.has(edge.fromSeqNo) || !seqNos.has(edge.toSeqNo)) {
      return '依赖边引用了不存在的工序 seqNo'
    }
    if (edge.fromSeqNo === edge.toSeqNo) {
      return '不允许工序自依赖'
    }
    if (!DEPEND_TYPES.includes(edge.dependType) || edge.lagMinutes < 0) {
      return '依赖类型或滞后时间不合法'
    }
    const key = `${edge.fromSeqNo}->${edge.toSeqNo}:${edge.dependType}`
    if (edgeKeys.has(key)) {
      return '存在重复的单向依赖边（同一 fromSeqNo/toSeqNo 与依赖类型）'
    }
    edgeKeys.add(key)
  }

  if (nodes.length > 1) {
    const incoming = new Set(edges.map((edge) => edge.toSeqNo))
    const outgoing = new Set(edges.map((edge) => edge.fromSeqNo))
    const isolated = nodes.find((node) => !incoming.has(node.seqNo) && !outgoing.has(node.seqNo))
    if (isolated) {
      return `存在孤立工序「${isolated.seqNo} · ${isolated.operationName || isolated.operationCode}」`
    }
  }

  const indegree = new Map<number, number>()
  const adjacency = new Map<number, number[]>()
  nodes.forEach((node) => {
    indegree.set(node.seqNo, 0)
    adjacency.set(node.seqNo, [])
  })
  edges.forEach((edge) => {
    adjacency.get(edge.fromSeqNo)?.push(edge.toSeqNo)
    indegree.set(edge.toSeqNo, (indegree.get(edge.toSeqNo) ?? 0) + 1)
  })
  const queue = nodes
    .filter((node) => (indegree.get(node.seqNo) ?? 0) === 0)
    .map((node) => node.seqNo)
  let visited = 0
  while (queue.length > 0) {
    const current = queue.shift() as number
    visited += 1
    for (const next of adjacency.get(current) ?? []) {
      const remaining = (indegree.get(next) ?? 0) - 1
      indegree.set(next, remaining)
      if (remaining === 0) {
        queue.push(next)
      }
    }
  }
  if (visited !== nodes.length) {
    return '工艺路线存在循环依赖，请检查依赖边方向'
  }

  return null
}

function resourceCodeOf(resourceId: string | null): string | null {
  if (!resourceId) {
    return null
  }
  return resources.find((resource) => resource.id === resourceId)?.code ?? null
}

/** 契约节点（无 id/position）转 Mock 存储节点；按 seqNo 复用旧节点位置。 */
function toMockRoutingNodes(
  payloads: RoutingNodePayload[],
  previous: RoutingNode[] = []
): RoutingNode[] {
  return payloads.map((payload, index) => {
    const prior = previous.find((node) => node.seqNo === payload.seqNo)
    const stored: RoutingNode = {
      seqNo: payload.seqNo,
      operationTemplateId: payload.operationTemplateId,
      operationCode: payload.operationCode,
      operationName: payload.operationName,
      standardSeconds: payload.standardSeconds ?? 0,
      setupSeconds: payload.setupSeconds ?? 0,
      primaryResourceId: payload.primaryResourceId,
      primaryResourceMode: payload.primaryResourceMode ?? 'INHERIT',
      optional: payload.optional,
      id: prior?.id ?? `node-${payload.seqNo}-${index}`,
      primaryResourceCode: resourceCodeOf(payload.primaryResourceId),
      position: prior?.position ?? { x: 80 + index * 260, y: 80 }
    }
    // 缺键与 [] 必须区别存储：显式空数组才写入字段。
    if (payload.secondaryResourceIds !== undefined) {
      stored.secondaryResourceIds = [...payload.secondaryResourceIds]
    } else {
      delete stored.secondaryResourceIds
    }
    return stored
  })
}

/**
 * Mock 读出序列化：历史 fixture 未写入 primaryResourceMode，当时 id 即显式指定，
 * 故按 FIXED 回显（兼容旧数据，仅存在于 Mock；前端不得用 id 反推模式）。
 */
function serializeRoutingNode(node: RoutingNode): RoutingNode {
  return {
    ...node,
    primaryResourceMode: node.primaryResourceMode ?? (node.primaryResourceId ? 'FIXED' : 'INHERIT')
  }
}

function serializeRouting(routing: Routing): Routing {
  return { ...routing, nodes: routing.nodes.map(serializeRoutingNode) }
}

function toMockRoutingEdges(payloads: RoutingEdgePayload[]): RoutingEdge[] {
  return payloads.map((payload, index) => ({
    ...payload,
    id: `edge-${payload.fromSeqNo}-${payload.toSeqNo}-${payload.dependType}-${index}`
  }))
}

function mockToday(): string {
  const date = new Date()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function sortItems<T>(
  items: T[],
  params: Record<string, unknown>,
  accessors: Record<string, (item: T) => string | number>
): T[] {
  const sort = String(params.sort ?? '').trim()
  if (!sort) {
    return items
  }
  const [field, direction] = sort.split(',')
  const accessor = accessors[field]
  if (!accessor) {
    return items
  }
  const factor = direction === 'desc' ? -1 : 1
  return [...items].sort((left, right) => {
    const leftValue = accessor(left)
    const rightValue = accessor(right)
    if (leftValue === rightValue) {
      return 0
    }
    return leftValue > rightValue ? factor : -factor
  })
}

/** 资源保存校验：复刻 C2 产能口径与日历引用约束（42200）。 */
function validateResourcePayload(body: Partial<ResourceCreateRequest>): string | null {
  const mode = body.capacityMode ?? 'UNIT_PER_HOUR'
  const capacityValue = Number(body.capacityValue ?? 0)
  const efficiency = body.efficiency ?? 1
  if (efficiency < 0.5 || efficiency > 1.5) {
    return '效率系数需在 0.5–1.5 之间'
  }
  if (mode === 'UNIT_PER_HOUR' && capacityValue <= 0) {
    return '件/小时模式下 capacity_value 必须大于 0'
  }
  if (mode === 'BATCH_SECONDS') {
    if (capacityValue <= 0) {
      return '批次秒模式下 capacity_value（每批秒数）必须大于 0'
    }
    if (Number(body.batchCapacity ?? 0) <= 0) {
      return '批次秒模式下 batch_capacity 必须大于 0'
    }
  }
  if (body.calendarId && !calendars.some((item) => item.id === body.calendarId)) {
    return '日历引用不存在（calendar_id 无效）'
  }
  return null
}

function handleResourceRequest(
  config: InternalAxiosRequestConfig,
  method: string,
  path: string,
  params: Record<string, unknown>
): MockReply | null {
  if (method === 'GET' && path === '/resources') {
    const code = String(params.code ?? '').trim()
    const name = String(params.name ?? '').trim()
    const type = String(params.type ?? '').trim()
    const groupId = String(params.groupId ?? '').trim()
    const status = String(params.status ?? '').trim()
    const filtered = resources.filter((item) => {
      if (code && !item.code.toLowerCase().includes(code.toLowerCase())) {
        return false
      }
      if (name && !item.name.includes(name)) {
        return false
      }
      if (type && item.type !== type) {
        return false
      }
      if (groupId && item.groupId !== groupId) {
        return false
      }
      if (status && (item.status ?? 'ACTIVE') !== status) {
        return false
      }
      return true
    })
    const sorted = sortItems(filtered, params, {
      code: (item) => item.code,
      name: (item) => item.name
    })
    return ok(config, paginate(sorted, params))
  }

  if (method === 'POST' && path === '/resources') {
    const body = readBody<Partial<ResourceCreateRequest>>(config)
    if (!body.code || !body.name) {
      return fail(config, 40000, '资源编码与名称不能为空', 400)
    }
    if (resources.some((item) => item.code === body.code)) {
      return fail(config, 40900, '资源编码已存在', 409)
    }
    const validation = validateResourcePayload(body)
    if (validation) {
      return fail(config, 42200, validation, 422)
    }
    const resource: Resource = {
      id: uuid(),
      code: body.code,
      name: body.name,
      type: body.type ?? 'PRIMARY',
      groupId: body.groupId ?? null,
      capacityMode: body.capacityMode ?? 'UNIT_PER_HOUR',
      capacityValue: body.capacityValue ?? 1,
      batchCapacity: body.batchCapacity ?? null,
      efficiency: body.efficiency ?? 1,
      calendarId: body.calendarId ?? null,
      setupSeconds: body.setupSeconds ?? 0,
      teardownSeconds: body.teardownSeconds ?? 0,
      maintenancePlan: {},
      industryAttrs: body.industryAttrs ?? {},
      status: 'ACTIVE',
      version: 1
    }
    resources.unshift(resource)
    return ok(config, resource)
  }

  const maintenanceMatch = path.match(/^\/resources\/([^/]+)\/maintenance$/)
  if (method === 'POST' && maintenanceMatch) {
    const code = decodeURIComponent(maintenanceMatch[1])
    const resource = resources.find((item) => item.code === code)
    if (!resource) {
      return fail(config, 40400, '资源不存在', 404)
    }
    resource.maintenancePlan = readBody<Record<string, unknown>>(config)
    resource.version += 1
    return ok(config, resource)
  }

  const resourceMatch = path.match(/^\/resources\/([^/]+)$/)
  if (resourceMatch) {
    const code = decodeURIComponent(resourceMatch[1])
    const resource = resources.find((item) => item.code === code)

    if (method === 'GET' && resourceMatch) {
      if (!resource) {
        return fail(config, 40400, '资源不存在', 404)
      }
      return ok(config, resource)
    }

    if (method === 'PUT') {
      const body = readBody<Partial<ResourceCreateRequest> & { version?: number }>(config)
      if (!resource) {
        return fail(config, 40400, '资源不存在', 404)
      }
      if (body.version !== undefined && body.version !== resource.version) {
        return fail(config, 40900, '资源已被他人修改，请刷新后重试', 409)
      }
      if (!body.name) {
        return fail(config, 40000, '资源名称不能为空', 400)
      }
      const validation = validateResourcePayload({ ...body, code: resource.code })
      if (validation) {
        return fail(config, 42200, validation, 422)
      }
      resource.name = body.name
      resource.type = body.type ?? resource.type
      resource.groupId = body.groupId ?? null
      resource.capacityMode = body.capacityMode ?? resource.capacityMode
      resource.capacityValue = body.capacityValue ?? resource.capacityValue
      resource.batchCapacity = body.batchCapacity ?? null
      resource.efficiency = body.efficiency ?? 1
      resource.calendarId = body.calendarId ?? null
      resource.setupSeconds = body.setupSeconds ?? 0
      resource.teardownSeconds = body.teardownSeconds ?? 0
      resource.industryAttrs = body.industryAttrs ?? resource.industryAttrs
      resource.version += 1
      return ok(config, resource)
    }

    if (method === 'DELETE') {
      if (!resource) {
        return fail(config, 40400, '资源不存在', 404)
      }
      resource.status = 'INACTIVE'
      resource.version += 1
      return ok(config, null)
    }
  }

  return null
}

function validateCalendarPayload(body: Partial<CalendarCreateRequest>): string | null {
  if (!body.name || !body.calendarVersion) {
    return '日历名称与版本不能为空'
  }
  if (!body.effectiveFrom) {
    return '生效日期不能为空'
  }
  if (body.effectiveTo && body.effectiveTo < body.effectiveFrom) {
    return '失效日期不能早于生效日期'
  }
  const shifts = body.shifts ?? []
  if (shifts.length === 0) {
    return '至少需要一个班次'
  }
  for (const shift of shifts) {
    if (!shift.startTime || !shift.endTime) {
      return '班次起止时间不能为空'
    }
    if (shift.startTime === shift.endTime) {
      return '班次起止时间不能相同'
    }
    if (!Array.isArray(shift.daysOfWeek) || shift.daysOfWeek.length === 0) {
      return '班次至少选择一个工作日'
    }
    if (shift.daysOfWeek.some((day) => day < 1 || day > 7)) {
      return '工作日取值必须为 1-7'
    }
  }
  for (const holiday of body.holidays ?? []) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(holiday.date)) {
      return '例外日期格式应为 yyyy-MM-dd'
    }
    if (!holiday.name) {
      return '例外日期名称不能为空'
    }
    if (!['LEGAL', 'COMPANY', 'MAKEUP'].includes(holiday.kind)) {
      return '例外日期类型只能为 LEGAL / COMPANY / MAKEUP'
    }
  }
  return null
}

function handleCalendarRequest(
  config: InternalAxiosRequestConfig,
  method: string,
  path: string,
  params: Record<string, unknown>
): MockReply | null {
  if (method === 'GET' && path === '/calendars') {
    const name = String(params.name ?? '').trim()
    const type = String(params.type ?? '').trim()
    const filtered = calendars.filter((item) => {
      if (name && !item.name.includes(name)) {
        return false
      }
      if (type && item.type !== type) {
        return false
      }
      return true
    })
    const sorted = sortItems(filtered, params, {
      effectiveFrom: (item) => item.effectiveFrom,
      name: (item) => item.name
    })
    return ok(config, paginate(sorted, params))
  }

  if (method === 'POST' && path === '/calendars') {
    const body = readBody<Partial<CalendarCreateRequest>>(config)
    const validation = validateCalendarPayload(body)
    if (validation) {
      const status = !body.name || !body.calendarVersion ? 400 : 422
      return fail(config, status === 400 ? 40000 : 42200, validation, status)
    }
    const calendar: Calendar = {
      id: uuid(),
      name: body.name as string,
      calendarVersion: body.calendarVersion as number,
      type: body.type ?? 'FACTORY',
      resourceId: body.resourceId ?? null,
      resourceGroupId: body.resourceGroupId ?? null,
      effectiveFrom: body.effectiveFrom as string,
      effectiveTo: body.effectiveTo ?? null,
      shifts: body.shifts ?? [],
      holidays: body.holidays ?? [],
      version: 1
    }
    calendars.unshift(calendar)
    return ok(config, calendar)
  }

  const availableMatch = path.match(/^\/calendars\/([^/]+)\/available$/)
  if (method === 'GET' && availableMatch) {
    return ok(config, availableTime)
  }

  const calendarMatch = path.match(/^\/calendars\/([^/]+)$/)
  if (calendarMatch) {
    const id = decodeURIComponent(calendarMatch[1])
    const calendar = calendars.find((item) => item.id === id)
    if (!calendar) {
      return fail(config, 40400, '日历不存在', 404)
    }

    if (method === 'GET') {
      return ok(config, calendar)
    }

    if (method === 'PUT') {
      // PRD：历史日历只读，服务端返回 403；前端编辑入口已提前拦截
      if (calendar.effectiveTo && calendar.effectiveTo < mockToday()) {
        return fail(config, 40300, '历史日历只读，请复制为新版本后修改', 403)
      }
      const body = readBody<Partial<CalendarCreateRequest> & { version?: number }>(config)
      if (body.version !== undefined && body.version !== calendar.version) {
        return fail(config, 40900, '日历已被他人修改，请刷新后重试', 409)
      }
      const validation = validateCalendarPayload(body)
      if (validation) {
        return fail(config, 42200, validation, 422)
      }
      calendar.name = body.name as string
      calendar.calendarVersion = body.calendarVersion as number
      calendar.type = body.type ?? calendar.type
      calendar.resourceId = body.resourceId ?? null
      calendar.resourceGroupId = body.resourceGroupId ?? null
      calendar.effectiveFrom = body.effectiveFrom as string
      calendar.effectiveTo = body.effectiveTo ?? null
      calendar.shifts = body.shifts ?? calendar.shifts
      calendar.holidays = body.holidays ?? []
      calendar.version += 1
      return ok(config, calendar)
    }
  }

  return null
}

const SOFT_WEIGHT_TOTAL = 100

/** 软约束权重合计：复刻 C19「权重和=100」口径，仅统计已启用的 SOFT 规则。 */
function sumEnabledSoftWeights(rules: StrategyRule[]): number {
  return rules
    .filter((rule) => rule.enabled && rule.type === 'SOFT')
    .reduce((total, rule) => total + (Number(rule.weight) || 0), 0)
}

/** 策略规则保存校验：规则代码唯一、至少一条启用 SOFT、权重和=100（42200）。 */
function validateStrategyRules(rules: StrategyRule[]): string | null {
  const codes = new Set<string>()
  for (const rule of rules) {
    if (!rule.code) {
      return '规则代码不能为空'
    }
    if (codes.has(rule.code)) {
      return `规则 ${rule.code} 重复，同一策略下规则代码必须唯一`
    }
    codes.add(rule.code)
    if (rule.type !== 'HARD' && rule.type !== 'SOFT') {
      return `规则 ${rule.code} 的 rule_type 必须为 HARD/SOFT`
    }
  }
  if (!rules.some((rule) => rule.enabled && rule.type === 'SOFT')) {
    return '至少需要一条启用的软约束规则'
  }
  const total = sumEnabledSoftWeights(rules)
  if (Math.abs(total - SOFT_WEIGHT_TOTAL) > 1e-6) {
    return `软约束权重之和必须为 ${SOFT_WEIGHT_TOTAL}，当前为 ${total}`
  }
  return null
}

const MOCK_SOLVE_RUN_MS = 6000

/**
 * Mock 异步求解：按 elapsed 推进进度，供 FE-05 轮询兜底联调。
 * 真实环境的事实源仍是 GET /solve-tasks/{id}，STOMP 只推送。
 */
function advanceMockSolveTask(detail: SolveTaskDetail): SolveTaskDetail {
  if (detail.status !== 'PENDING' && detail.status !== 'RUNNING') {
    return detail
  }
  const startedAt = detail.startedAt ? Date.parse(detail.startedAt) : Date.now()
  const elapsed = Math.max(0, Date.now() - startedAt)
  detail.elapsedMs = elapsed
  if (elapsed < 800) {
    detail.status = 'PENDING'
    detail.progress = 0
    detail.phase = 'ASSEMBLING'
    detail.bestScore = null
    return detail
  }
  if (elapsed < MOCK_SOLVE_RUN_MS) {
    const ratio = (elapsed - 800) / (MOCK_SOLVE_RUN_MS - 800)
    detail.status = 'RUNNING'
    detail.progress = Math.min(0.95, ratio * 0.95)
    detail.phase = elapsed < 2600 ? 'INITIAL' : 'LOCAL_SEARCH'
    detail.bestScore = `0hard/${Math.round(-400 + ratio * 355)}soft`
    return detail
  }
  detail.status = 'SOLVED'
  detail.progress = 1
  detail.phase = 'FINISHED'
  detail.bestScore = '0hard/-45soft'
  detail.scoreHard = 0
  detail.scoreSoft = -45
  detail.resultTaskId = detail.id
  detail.finishedAt = now()
  return detail
}

/**
 * 计划版本 Mock（C24）：只做后端已实现的三件事（详情/发布/回滚）。
 * 重复发布同样幂等返回 200；Mock 不模拟 40903（该码需要场景内存在 IN_PROGRESS/COMPLETED 任务）。
 */
const mockPlanRecords = new Map<string, PlanDetail>()

function createMockPlan(planId: string): PlanDetail {
  const tasks = ganttTasks
    .filter((task) => task.status === 'PLANNED')
    .slice(0, 3)
    .map((task) => ({
      taskId: String(task.id),
      orderNo: task.orderNo ?? null,
      operationIndex: task.operationIndex ?? null,
      status: task.status,
      startTime: task.startTime ?? null,
      endTime: task.endTime ?? null,
      resourceId: task.resourceCode ?? null,
      qty: task.qty ?? null
    }))
  return {
    action: {
      planId,
      planNo: 'PLAN-20261104-001',
      version: 1,
      status: 'DRAFT',
      publishedAt: null,
      affectedTaskCount: 0,
      sandboxId: DEFAULT_SANDBOX_ID,
      taskCount: 0,
      approver: null,
      reason: 'Mock 计划版本',
      rollbackOf: null
    },
    tasks
  }
}

function mockPlanOf(planId: string): PlanDetail {
  const existing = mockPlanRecords.get(planId)
  if (existing) {
    return existing
  }
  const created = createMockPlan(planId)
  mockPlanRecords.set(planId, created)
  return created
}

const MOCK_SEED_PLAN_IDS = [
  'a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1',
  'b2b2b2b2-b2b2-4b2b-8b2b-b2b2b2b2b2b2',
  'c3c3c3c3-c3c3-4c3c-8c3c-c3c3c3c3c3c3'
]

/** Mock 对比页首次进入时补齐 3 个版本，避免必须先在“计划发布”页造数。 */
function ensureSeedPlans(sandboxId: string): void {
  const existing = [...mockPlanRecords.values()].filter(
    (record) => record.action.sandboxId === sandboxId
  )
  if (existing.length > 0) {
    return
  }
  MOCK_SEED_PLAN_IDS.forEach((planId, index) => {
    const plan = createMockPlan(planId)
    plan.action = {
      ...plan.action,
      planNo: `PLAN-20261104-${String(index + 1).padStart(3, '0')}`,
      version: index + 1,
      status: index === 0 ? 'PUBLISHED' : 'DRAFT',
      publishedAt: index === 0 ? '2026-11-03T02:00:16Z' : null,
      sandboxId,
      taskCount: plan.tasks.length,
      affectedTaskCount: plan.tasks.length,
      reason: `Mock 对比版本 v${index + 1}`
    }
    mockPlanRecords.set(planId, plan)
  })
}

const XLSX_MEDIA_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

const MOCK_IMPORT_ENTITY_SLUGS = new Set(IMPORT_ENTITY_OPTIONS.map((entity) => entity.slug))

interface MockImportJobRecord {
  view: ImportJobView
  polls: number
  fail: boolean
}

const mockImportJobs = new Map<string, MockImportJobRecord>()

function rawReply(body: unknown, status = 200): MockReply {
  return { status, body }
}

function binaryReply(fileName: string, content: string): MockReply {
  const body = typeof Blob === 'undefined' ? content : new Blob([content], { type: XLSX_MEDIA_TYPE })
  return {
    status: 200,
    body,
    headers: {
      'content-type': XLSX_MEDIA_TYPE,
      'content-disposition': `attachment; filename="${fileName}"`
    }
  }
}

function readUploadedFile(
  config: InternalAxiosRequestConfig
): { name: string; size: number } | null {
  const raw = config.data
  if (typeof FormData === 'undefined' || !(raw instanceof FormData)) {
    return null
  }
  const value = raw.get('file')
  if (!value) {
    return null
  }
  if (typeof value === 'string') {
    return { name: 'upload.xlsx', size: 0 }
  }
  return { name: value.name || 'upload.xlsx', size: value.size }
}

/** 文件名含 fail/bad/error 时模拟失败批次，便于走查行级错误定位。 */
function mockImportFails(fileName: string): boolean {
  return /fail|bad|error/i.test(fileName)
}

function mockImportErrors(entity: string): ImportError[] {
  const columns = findImportEntity(entity)?.columns ?? []
  const rows = [3, 6, 9]
  return rows.map((row, index) => {
    const column = columns[Math.min(index * 2, Math.max(columns.length - 1, 0))] ?? columns[0]
    return {
      row,
      field: column?.key ?? 'header',
      code: 42200,
      message: `${column?.header ?? '字段'}校验未通过`
    }
  })
}

function mockPreviewRows(entity: string, count: number): Record<string, unknown>[] {
  const columns = findImportEntity(entity)?.columns ?? []
  return Array.from({ length: count }, (_, index) => {
    const row: Record<string, unknown> = {}
    columns.forEach((column, columnIndex) => {
      if (column.key === 'code') {
        row[column.key] = `CUS-${String(index + 1).padStart(3, '0')}`
      } else if (column.key === 'name') {
        row[column.key] = `Mock 客户 ${index + 1}`
      } else if (column.key === 'priority') {
        row[column.key] = ['VIP', 'NORMAL', 'LOW'][index % 3]
      } else if (column.key === 'leadTimeDays') {
        row[column.key] = index + 1
      } else if (column.key === 'paymentTerms') {
        row[column.key] = '月结30天'
      } else if (column.key === 'blacklisted') {
        row[column.key] = index % 4 === 0
      } else {
        row[column.key] = `${column.key}-${index + 1}-${columnIndex}`
      }
    })
    return row
  })
}

function mockSyncResult(
  entity: string,
  fail: boolean,
  mode: 'PREVIEW' | 'EXECUTE'
): ImportResult {
  const totalRows = 10
  const invalidRows = fail ? 3 : 0
  const committed = mode === 'EXECUTE' && !fail
  return {
    entity,
    mode,
    totalRows,
    validRows: totalRows - invalidRows,
    invalidRows,
    inserted: committed ? 8 : 0,
    updated: committed ? 2 : 0,
    skipped: 0,
    committed,
    replayed: false,
    errors: fail ? mockImportErrors(entity) : [],
    preview: mockPreviewRows(entity, totalRows)
  }
}

function createMockAsyncJob(entity: string, fail: boolean): ImportJobView {
  const jobId = uuid()
  const view: ImportJobView = {
    jobId,
    entity,
    mode: 'async',
    status: 'PENDING',
    totalRows: 10,
    processedRows: 0,
    inserted: 0,
    updated: 0,
    skipped: 0,
    invalidRows: 0,
    errors: [],
    committed: false,
    contentHash: `sha256:mock-${jobId.replace(/-/g, '').slice(0, 16)}`,
    createdAt: now(),
    startedAt: null,
    finishedAt: null
  }
  mockImportJobs.set(jobId, { view, polls: 0, fail })
  return view
}

function isTerminalImportStatus(status: ImportJobStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED'
}

function advanceMockImportJob(jobId: string): ImportJobView | null {
  const record = mockImportJobs.get(jobId)
  if (!record) {
    return null
  }
  if (isTerminalImportStatus(record.view.status)) {
    return record.view
  }
  record.polls += 1
  if (record.view.status === 'PENDING') {
    record.view = {
      ...record.view,
      status: 'RUNNING',
      startedAt: now(),
      processedRows: 2
    }
    return record.view
  }
  record.view = record.fail
    ? {
        ...record.view,
        status: 'FAILED',
        processedRows: record.view.totalRows,
        invalidRows: 3,
        errors: mockImportErrors(record.view.entity),
        committed: false,
        finishedAt: now()
      }
    : {
        ...record.view,
        status: 'SUCCEEDED',
        processedRows: record.view.totalRows,
        inserted: 8,
        updated: 2,
        committed: true,
        finishedAt: now()
      }
  return record.view
}

export function handleMockRequest(config: InternalAxiosRequestConfig): MockReply {
  const method = (config.method ?? 'get').toUpperCase()
  const path = resolvePath(config)
  const params = readParams(config)

  if (method === 'GET' && path === '/health') {
    return ok(config, { status: 'UP', service: 'aps-admin (mock)' })
  }

  // C30 登录：Mock 与真实后端保持同一错误口径——密码错误/禁用/锁定统一 401 + 40100，
  // 不在前端伪造 423（后端当前不返回 423，页面仅做防御性映射）。
  if (method === 'POST' && path === '/auth/login') {
    const body = readBody<Partial<LoginRequest>>(config)
    if (!body.username || !body.password) {
      return fail(config, 40000, '用户名和密码不能为空', 400)
    }
    const account = users.find((item) => item.username === body.username)
    if (!account || body.password !== 'admin123' || account.status !== 'ACTIVE') {
      return fail(config, 40100, '用户名或密码错误', 401)
    }
    account.lastLoginAt = now()
    return ok(config, {
      accessToken: 'mock-access-token',
      tokenType: 'Bearer',
      expiresIn: 28800,
      userId: account.id
    })
  }

  // C30 GET /me：权限 = 用户全部角色权限并集。
  if (method === 'GET' && path === '/me') {
    const current = users.find((item) => item.username === 'admin') ?? users[0]
    const roleSet = new Set(roles.filter((role) => current.roleCodes.includes(role.code)))
    const permissionSet = new Set<PermissionCode>()
    roleSet.forEach((role) => role.permissionCodes.forEach((code) => permissionSet.add(code)))
    const me: MeView = { ...current, permissions: [...permissionSet] }
    return ok(config, me)
  }

  if (method === 'GET' && path === '/users') {
    const status = String(params.status ?? '').trim()
    const filtered = status ? users.filter((item) => item.status === status) : users
    return ok(config, paginate(filtered, params))
  }

  if (method === 'POST' && path === '/users') {
    const body = readBody<Partial<UserCreateRequest>>(config)
    if (!body.username || !body.password) {
      return fail(config, 40000, '用户名与初始密码不能为空', 400)
    }
    if (users.some((item) => item.username === body.username)) {
      return fail(config, 40900, '用户名已存在', 409)
    }
    const user: User = {
      id: uuid(),
      username: body.username,
      displayName: body.displayName ?? null,
      email: body.email ?? null,
      phone: body.phone ?? null,
      status: 'ACTIVE',
      lastLoginAt: null,
      version: 1,
      roleCodes: body.roleCodes ?? ['VIEWER']
    }
    users.unshift(user)
    return ok(config, user)
  }

  const userMatch = path.match(/^\/users\/([^/]+)$/)
  if (method === 'PATCH' && userMatch) {
    const userId = decodeURIComponent(userMatch[1])
    const user = users.find((item) => item.id === userId)
    if (!user) {
      return fail(config, 40400, '用户不存在', 404)
    }
    const body = readBody<Partial<UserUpdateRequest>>(config)
    if (typeof body.version !== 'number' || body.version !== user.version) {
      return fail(config, 40901, '用户已被他人修改，请刷新后重试', 409)
    }
    if (body.displayName !== undefined) {
      user.displayName = body.displayName
    }
    if (body.email !== undefined) {
      user.email = body.email
    }
    if (body.phone !== undefined) {
      user.phone = body.phone
    }
    if (body.status) {
      user.status = body.status
    }
    if (body.roleCodes) {
      user.roleCodes = body.roleCodes
    }
    user.version += 1
    return ok(config, user)
  }

  // C30 GET /roles：按契约返回 {items}（不是裸数组）。
  if (method === 'GET' && path === '/roles') {
    return ok(config, { items: roles })
  }

  if (method === 'POST' && path === '/roles') {
    const body = readBody<Partial<RoleCreateRequest>>(config)
    if (!body.code || !body.name) {
      return fail(config, 40000, '角色码与角色名称不能为空', 400)
    }
    if (roles.some((item) => item.code === body.code)) {
      return fail(config, 40900, '角色码已存在', 409)
    }
    const role: RoleView = {
      id: uuid(),
      code: body.code,
      name: body.name,
      description: body.description ?? null,
      permissionCodes: body.permissionCodes ?? [],
      version: 1
    }
    roles.push(role)
    return ok(config, role)
  }

  const rolePermissionMatch = path.match(/^\/roles\/([^/]+)\/permissions$/)
  if (method === 'PUT' && rolePermissionMatch) {
    const role = roles.find((item) => item.id === decodeURIComponent(rolePermissionMatch[1]))
    if (!role) {
      return fail(config, 40400, '角色不存在', 404)
    }
    const body = readBody<{ permissionCodes?: string[] }>(config)
    if (!Array.isArray(body.permissionCodes)) {
      return fail(config, 40000, 'permissionCodes 必填，保存为全量替换', 400)
    }
    const legal = new Set(permissions.map((item) => item.code as string))
    const invalid = body.permissionCodes.find((code) => !legal.has(code))
    if (invalid) {
      return fail(config, 42200, `权限码 ${invalid} 不在权限闭集内`, 422)
    }
    role.permissionCodes = body.permissionCodes as PermissionCode[]
    role.version += 1
    return ok(config, role)
  }

  // C30 GET /permissions：真实响应就是裸数组（与 /roles 的 {items} 不一致，前端按真实形状解析）。
  if (method === 'GET' && path === '/permissions') {
    return ok(config, permissions)
  }

  // C31 审计：过滤条件 AND，时间区间 [from, to)。
  if (method === 'GET' && path === '/audit-logs') {
    const action = String(params.action ?? '').trim()
    const resourceType = String(params.resourceType ?? '').trim()
    const resourceId = String(params.resourceId ?? '').trim()
    const userId = String(params.userId ?? '').trim()
    const from = String(params.from ?? '').trim()
    const to = String(params.to ?? '').trim()
    const fromMs = from ? Date.parse(from) : Number.NEGATIVE_INFINITY
    const toMs = to ? Date.parse(to) : Number.POSITIVE_INFINITY
    const filtered = auditLogs
      .filter((item: AuditLog) => {
        if (action && item.action !== action) {
          return false
        }
        if (resourceType && item.resourceType !== resourceType) {
          return false
        }
        if (resourceId && item.resourceId !== resourceId) {
          return false
        }
        if (userId && item.userId !== userId) {
          return false
        }
        const createdMs = Date.parse(item.createdAt)
        return createdMs >= fromMs && createdMs < toMs
      })
      .sort((left, right) => {
        const createdDiff = Date.parse(right.createdAt) - Date.parse(left.createdAt)
        return createdDiff !== 0 ? createdDiff : Number(right.id) - Number(left.id)
      })
    return ok(config, paginate(filtered, params))
  }

  if (method === 'GET' && path === '/configs') {
    return ok(config, configs)
  }

  if (method === 'PUT' && path === '/configs') {
    const body = readBody<Partial<ConfigUpdateRequest>>(config)
    body.items?.forEach((item) => {
      const existing = configs.find((entry) => entry.key === item.key)
      if (existing) {
        existing.value = item.value
        existing.updatedAt = now()
      } else {
        configs.push({ key: item.key, value: item.value, description: null, updatedAt: now() })
      }
    })
    return ok(config, configs)
  }

  if (method === 'GET' && path === '/materials') {
    const type = params.type
    const codeKeyword = String(params.code ?? '')
      .trim()
      .toLowerCase()
    const nameKeyword = String(params.name ?? '')
      .trim()
      .toLowerCase()
    // 物料选择器会把同一关键字同时放进 code/name，此时按 OR 处理；独立筛选时按 AND。
    const sharedKeyword =
      codeKeyword && nameKeyword && codeKeyword === nameKeyword ? codeKeyword : ''
    const filtered = materials.filter((item) => {
      if (type && item.type !== type) {
        return false
      }
      if (sharedKeyword) {
        return (
          item.code.toLowerCase().includes(sharedKeyword) ||
          item.name.toLowerCase().includes(sharedKeyword)
        )
      }
      if (codeKeyword && !item.code.toLowerCase().includes(codeKeyword)) {
        return false
      }
      if (nameKeyword && !item.name.toLowerCase().includes(nameKeyword)) {
        return false
      }
      return true
    })
    return ok(config, paginate(filtered, params))
  }

  if (method === 'POST' && path === '/materials') {
    const body = readBody<Partial<MaterialCreateRequest>>(config)
    if (!body.code || !body.name) {
      return fail(config, 40000, '物料编码与名称不能为空', 400)
    }
    if (!body.type) {
      return fail(config, 40000, '物料类型不能为空', 400)
    }
    if (!body.unit) {
      return fail(config, 40000, '计量单位不能为空', 400)
    }
    if (materials.some((item) => item.code === body.code)) {
      return fail(config, 40900, '物料编码已存在', 409)
    }
    const material: Material = {
      code: body.code,
      name: body.name,
      type: body.type,
      spec: body.spec,
      unit: body.unit,
      categoryId: body.categoryId ?? null,
      safetyStock: body.safetyStock ?? 0,
      moq: body.moq ?? 0,
      leadTimeDays: body.leadTimeDays ?? 0,
      defaultSupplierId: body.defaultSupplierId ?? null,
      extAttrs: body.extAttrs ?? {},
      industryAttrs: body.industryAttrs ?? {},
      version: 1,
      createdAt: now(),
      updatedAt: now()
    }
    materials.unshift(material)
    return ok(config, material)
  }

  const materialDetail = path.match(/^\/materials\/([^/]+)$/)
  if (materialDetail) {
    const code = decodeURIComponent(materialDetail[1])
    const index = materials.findIndex((item) => item.code === code)
    if (index < 0) {
      return fail(config, 40400, '物料不存在', 404)
    }
    if (method === 'GET') {
      return ok(config, materials[index])
    }
    if (method === 'PUT') {
      const body = readBody<Partial<Material>>(config)
      if (body.version !== materials[index].version) {
        return fail(config, 40901, '物料已被修改，请刷新后重试', 409)
      }
      const updated: Material = {
        ...materials[index],
        ...body,
        code,
        version: materials[index].version + 1,
        updatedAt: now()
      }
      materials[index] = updated
      return ok(config, updated)
    }
    if (method === 'DELETE') {
      const referencedByBom = boms.some(
        (bom) =>
          bom.parentMaterialCode === code || bom.items.some((item) => item.materialCode === code)
      )
      const referencedByOrder = orders.some((order) => order.materialCode === code)
      if (referencedByBom || referencedByOrder) {
        return fail(config, 42200, '物料已被 BOM 或订单引用，无法停用', 422)
      }
      materials.splice(index, 1)
      return ok(config, null)
    }
  }

  if (method === 'GET' && path === '/boms') {
    const parentMaterialCode = String(params.parentMaterialCode ?? '')
    const status = String(params.status ?? '')
    const filtered = boms.filter((item) => {
      if (parentMaterialCode && item.parentMaterialCode !== parentMaterialCode) {
        return false
      }
      if (status && item.status !== status) {
        return false
      }
      return true
    })
    return ok(config, paginate(filtered, params))
  }

  if (method === 'POST' && path === '/boms') {
    const body = readBody<Partial<BomCreateRequest>>(config)
    const items = Array.isArray(body.items) ? body.items : []
    if (!body.parentMaterialCode || !body.bomVersion || !body.effectiveDate) {
      return fail(config, 40000, '父件物料、版本与生效日期不能为空', 400)
    }
    if (items.length === 0) {
      return fail(config, 42200, 'BOM 明细至少需要一行', 422)
    }
    if (!materials.some((item) => item.code === body.parentMaterialCode)) {
      return fail(config, 42200, `父件物料 ${body.parentMaterialCode} 不存在`, 422)
    }
    const invalidItem = items.find((item) => {
      if (!item.materialCode || !materials.some((entry) => entry.code === item.materialCode)) {
        return true
      }
      return !(item.qtyPerUnit > 0)
    })
    if (invalidItem) {
      return fail(
        config,
        42200,
        `组件物料 ${invalidItem.materialCode ?? '（空）'} 不存在或单位用量不大于 0`,
        422
      )
    }
    if (
      boms.some(
        (item) =>
          item.parentMaterialCode === body.parentMaterialCode && item.bomVersion === body.bomVersion
      )
    ) {
      return fail(config, 40900, '同一父件下 BOM 版本已存在', 409)
    }
    const bom: Bom = {
      id: uuid(),
      parentMaterialCode: body.parentMaterialCode,
      bomVersion: body.bomVersion,
      effectiveDate: body.effectiveDate,
      expiryDate: body.expiryDate ?? null,
      status: 'DRAFT',
      isPanel: body.isPanel ?? false,
      panelSize: body.panelSize ?? null,
      items,
      version: 1
    }
    boms.unshift(bom)
    return ok(config, bom)
  }

  const bomTreeMatch = path.match(/^\/boms\/([^/]+)\/tree$/)
  if (method === 'GET' && bomTreeMatch) {
    const tree = buildBomTree(decodeURIComponent(bomTreeMatch[1]))
    if (!tree) {
      return fail(config, 40400, 'BOM 不存在', 404)
    }
    return ok(config, tree)
  }

  const bomActivateMatch = path.match(/^\/boms\/([^/]+)\/activate$/)
  if (method === 'POST' && bomActivateMatch) {
    const bom = boms.find((item) => item.id === decodeURIComponent(bomActivateMatch[1]))
    if (!bom) {
      return fail(config, 40400, 'BOM 不存在', 404)
    }
    // Mock 按 C8 收敛为单 ACTIVE；真实后端仍可能返回 409，前端已提前提示。
    boms.forEach((item) => {
      if (item.parentMaterialCode === bom.parentMaterialCode) {
        item.status = 'INACTIVE'
      }
    })
    bom.status = 'ACTIVE'
    bom.version += 1
    return ok(config, bom)
  }

  const resourceReply = handleResourceRequest(config, method, path, params)
  if (resourceReply) {
    return resourceReply
  }

  const calendarReply = handleCalendarRequest(config, method, path, params)
  if (calendarReply) {
    return calendarReply
  }

  if (method === 'GET' && path === '/operation-templates') {
    const code = String(params.code ?? '')
      .trim()
      .toLowerCase()
    const name = String(params.name ?? '')
      .trim()
      .toLowerCase()
    const resourceType = String(params.resourceType ?? '').trim()
    const filtered = operationTemplates.filter((template) => {
      const hasKeyword = Boolean(code || name)
      const keywordMatched =
        !hasKeyword ||
        (code ? template.code.toLowerCase().includes(code) : false) ||
        (name ? template.name.toLowerCase().includes(name) : false)
      if (!keywordMatched) {
        return false
      }
      if (resourceType) {
        const primary = resources.find((resource) => resource.id === template.primaryResourceId)
        if (!primary || primary.type !== resourceType) {
          return false
        }
      }
      return true
    })
    const sorted = sortItems(filtered, params, {
      code: (item) => item.code,
      name: (item) => item.name
    })
    return ok(config, paginate(sorted, params))
  }

  if (method === 'GET' && path === '/routings') {
    const productCode = String(params.productCode ?? '').trim()
    const version = String(params.version ?? '')
      .trim()
      .toLowerCase()
    const status = String(params.status ?? '')
    const filtered = routings.filter((item) => {
      if (productCode && item.productCode !== productCode) {
        return false
      }
      if (version && item.routingVersion.toLowerCase() !== version) {
        return false
      }
      if (status && item.status !== status) {
        return false
      }
      return true
    })
    return ok(config, paginate(filtered, params))
  }

  if (method === 'POST' && path === '/routings') {
    const body = readBody<Partial<RoutingCreateRequest>>(config)
    const productCode = body.productCode?.trim() ?? ''
    const routingVersion = body.routingVersion?.trim() ?? ''
    if (!productCode || !routingVersion) {
      return fail(config, 40000, '产品编码与版本不能为空', 400)
    }
    if (!body.effectiveDate) {
      return fail(config, 40000, '生效日期不能为空', 400)
    }
    if (!materials.some((item) => item.code === productCode)) {
      return fail(config, 42200, `产品物料 ${productCode} 不存在`, 422)
    }
    if (
      routings.some(
        (item) => item.productCode === productCode && item.routingVersion === routingVersion
      )
    ) {
      return fail(config, 40900, '同一产品下工艺路线版本已存在', 409)
    }
    const nodes = Array.isArray(body.nodes) ? body.nodes : []
    const edges = Array.isArray(body.edges) ? body.edges : []
    const error = validateRoutingPayload(nodes, edges)
    if (error) {
      return fail(config, 42200, error, 422)
    }
    const routing: Routing = {
      id: uuid(),
      productCode,
      productName: materialNameOf(productCode),
      routingVersion,
      effectiveDate: body.effectiveDate,
      expiryDate: body.expiryDate ?? null,
      status: 'DRAFT',
      nodes: toMockRoutingNodes(nodes),
      edges: toMockRoutingEdges(edges),
      version: 1,
      updatedAt: now()
    }
    routings.unshift(routing)
    return ok(config, routing)
  }

  const routingDetailMatch = path.match(/^\/routings\/([^/]+)$/)
  if (routingDetailMatch && method === 'GET') {
    const routingId = decodeURIComponent(routingDetailMatch[1])
    const routing = routings.find((item) => item.id === routingId)
    if (!routing) {
      return fail(config, 40400, '工艺路线不存在', 404)
    }
    return ok(config, serializeRouting(routing))
  }

  const routingGraphMatch = path.match(/^\/routings\/([^/]+)\/graph$/)
  if (routingGraphMatch) {
    const routingId = decodeURIComponent(routingGraphMatch[1])
    const routing = routings.find((item) => item.id === routingId)
    if (!routing) {
      return fail(config, 40400, '工艺路线不存在', 404)
    }
    if (method === 'GET') {
      return ok(config, { ...serializeRouting(routing), routingId: routing.id })
    }
    if (method === 'PUT') {
      if (routing.status !== 'DRAFT') {
        const reason = routing.status === 'ACTIVE' ? '生效版本只读' : '历史版本只读'
        return fail(config, 42200, `${reason}，请新建版本后再编辑`, 422)
      }
      const body = readBody<Partial<RoutingGraphUpdateRequest>>(config)
      if (typeof body.version !== 'number') {
        return fail(config, 40000, 'version 为必填乐观锁字段', 400)
      }
      if (body.version !== routing.version) {
        return fail(config, 40901, '工艺路线已被修改，请刷新后重试', 409)
      }
      if (!Array.isArray(body.nodes) || !Array.isArray(body.edges)) {
        return fail(config, 40000, 'nodes 与 edges 必填，保存为整体替换', 400)
      }
      const nodes = body.nodes
      const edges = body.edges
      const error = validateRoutingPayload(nodes, edges)
      if (error) {
        return fail(config, 42200, error, 422)
      }
      routing.nodes = toMockRoutingNodes(nodes, routing.nodes)
      routing.edges = toMockRoutingEdges(edges)
      routing.version += 1
      routing.updatedAt = now()
      return ok(config, { ...serializeRouting(routing), routingId: routing.id })
    }
  }

  if (method === 'POST' && path === '/mrp/analysis') {
    const body = readBody<Partial<MrpAnalysisParams>>(config)
    const blocking = body.blocking === true
    const orderNos = Array.isArray(body.orderNos) ? body.orderNos : []
    const orders = orderNos.length
      ? mrpAnalysisFixture.orders.filter((order) => orderNos.includes(order.orderNo))
      : mrpAnalysisFixture.orders
    const includeSuggestions = body.includeSuggestions !== false
    const shortages = includeSuggestions
      ? mrpAnalysisFixture.shortages
      : mrpAnalysisFixture.shortages.map((shortage) => ({
          ...shortage,
          suggestedPurchaseDate: null,
          suggestedQty: null,
          supplierCode: null
        }))
    const result: MrpAnalysisResult = {
      ...mrpAnalysisFixture,
      sandboxId: body.sandboxId ?? DEFAULT_SANDBOX_ID,
      analyzedAt: now(),
      blocking,
      blocked: blocking && mrpAnalysisFixture.status !== 'FULL',
      orderCount: orders.length,
      orders,
      shortages
    }
    return ok(config, result)
  }

  if (method === 'GET' && path === '/orders') {
    const orderNoKeyword = String(params.orderNo ?? '')
      .trim()
      .toLowerCase()
    const materialKeyword = String(params.materialCode ?? '')
      .trim()
      .toLowerCase()
    const customerKeyword = String(params.customerId ?? '')
      .trim()
      .toLowerCase()
    const status = String(params.status ?? '')
    const dueDateFrom = String(params.dueDateFrom ?? '')
    const dueDateTo = String(params.dueDateTo ?? '')
    const filtered = orders.filter((item) => {
      if (orderNoKeyword && !item.orderNo.toLowerCase().includes(orderNoKeyword)) {
        return false
      }
      if (materialKeyword && !item.materialCode.toLowerCase().includes(materialKeyword)) {
        return false
      }
      if (
        customerKeyword &&
        !String(item.customerId ?? '')
          .toLowerCase()
          .includes(customerKeyword)
      ) {
        return false
      }
      if (status && item.status !== status) {
        return false
      }
      if (dueDateFrom && item.dueDate < dueDateFrom) {
        return false
      }
      if (dueDateTo && item.dueDate > dueDateTo) {
        return false
      }
      return true
    })
    return ok(config, paginate(filtered, params))
  }

  if (method === 'POST' && path === '/orders') {
    const body = readBody<Partial<OrderCreateRequest>>(config)
    if (!body.orderNo || !body.materialCode) {
      return fail(config, 40000, '订单号与物料编码不能为空', 400)
    }
    if (!body.dueDate) {
      return fail(config, 40000, '交期不能为空', 400)
    }
    if (!(Number(body.qty) > 0)) {
      return fail(config, 40000, '订单数量必须大于 0', 400)
    }
    if (orders.some((item) => item.orderNo === body.orderNo)) {
      return fail(config, 40900, '订单号已存在，请更换后重试', 409)
    }
    if (!materials.some((item) => item.code === body.materialCode)) {
      return fail(config, 42200, `产品 ${body.materialCode} 不存在`, 422)
    }
    const order: Order = {
      orderNo: body.orderNo,
      materialCode: body.materialCode,
      qty: body.qty ?? 0,
      status: 'DRAFT',
      freezeStatus: 'UNFROZEN',
      dueDate: body.dueDate ?? now().slice(0, 10),
      priority: body.priority ?? 'NORMAL',
      source: body.source ?? 'MANUAL',
      customerId: body.customerId ?? null,
      routingId: body.routingId ?? null,
      remark: body.remark ?? null,
      extAttrs: body.extAttrs ?? {},
      version: 1,
      createdAt: now(),
      updatedAt: now()
    }
    orders.unshift(order)
    return ok(config, order)
  }

  if (method === 'POST' && path === '/orders/atp') {
    return ok(config, {
      feasible: true,
      earliestDate: '2026-11-12',
      leadTimeDays: 8,
      bottleneckResourceCode: 'RES-AGING-01'
    })
  }

  const confirmMatch = path.match(/^\/orders\/([^/]+)\/confirm$/)
  if (method === 'PUT' && confirmMatch) {
    const order = orders.find((item) => item.orderNo === decodeURIComponent(confirmMatch[1]))
    if (!order) {
      return fail(config, 40400, '订单不存在', 404)
    }
    if (order.status !== 'DRAFT') {
      return fail(config, 40901, '仅草稿订单可确认', 409)
    }
    // C8：产品存在、BOM 唯一 ACTIVE、工艺路线存在，命中失败一律 42200 并直出 message。
    if (!(order.qty > 0) || !order.dueDate) {
      return fail(config, 40000, '订单数量必须大于 0 且交期不能为空', 400)
    }
    const product = materials.find((item) => item.code === order.materialCode)
    if (!product) {
      return fail(config, 42200, `产品 ${order.materialCode} 不存在，无法确认订单`, 422)
    }
    const activeBoms = boms.filter(
      (item) => item.parentMaterialCode === order.materialCode && item.status === 'ACTIVE'
    )
    if (activeBoms.length === 0) {
      return fail(
        config,
        42200,
        `产品 ${order.materialCode} 缺少唯一 ACTIVE 的 BOM，无法确认订单`,
        422
      )
    }
    if (activeBoms.length > 1) {
      return fail(
        config,
        42200,
        `产品 ${order.materialCode} 存在 ${activeBoms.length} 条 ACTIVE BOM，数据不一致`,
        422
      )
    }
    const routing = order.routingId
      ? routings.find((item) => item.id === order.routingId)
      : routings.find((item) => item.productCode === order.materialCode)
    if (!routing) {
      return fail(
        config,
        42200,
        `产品 ${order.materialCode} 缺少唯一 ACTIVE 的工艺路线，无法确认订单`,
        422
      )
    }
    order.status = 'CONFIRMED'
    order.version += 1
    order.updatedAt = now()
    return ok(config, order)
  }

  const freezeMatch = path.match(/^\/orders\/([^/]+)\/freeze$/)
  if (method === 'PUT' && freezeMatch) {
    const order = orders.find((item) => item.orderNo === decodeURIComponent(freezeMatch[1]))
    if (!order) {
      return fail(config, 40400, '订单不存在', 404)
    }
    const body = readBody<Partial<OrderFreezeRequest>>(config)
    if (body.version !== order.version) {
      return fail(config, 40901, '订单已被修改，请刷新后重试', 409)
    }
    order.freezeStatus = body.freezeStatus ?? 'UNFROZEN'
    order.version += 1
    order.updatedAt = now()
    return ok(config, order)
  }

  const orderDetail = path.match(/^\/orders\/([^/]+)$/)
  if (method === 'GET' && orderDetail) {
    const order = orders.find((item) => item.orderNo === decodeURIComponent(orderDetail[1]))
    if (!order) {
      return fail(config, 40400, '订单不存在', 404)
    }
    return ok(config, order)
  }

  if (method === 'PUT' && orderDetail) {
    const order = orders.find((item) => item.orderNo === decodeURIComponent(orderDetail[1]))
    if (!order) {
      return fail(config, 40400, '订单不存在', 404)
    }
    if (order.status !== 'DRAFT') {
      return fail(config, 40901, '仅草稿订单可编辑', 409)
    }
    const body = readBody<Partial<OrderUpdateRequest>>(config)
    if (body.version !== order.version) {
      return fail(config, 40901, '订单已被修改，请刷新后重试', 409)
    }
    if (!body.materialCode || !body.dueDate) {
      return fail(config, 40000, '物料编码与交期不能为空', 400)
    }
    if (!(Number(body.qty) > 0)) {
      return fail(config, 40000, '订单数量必须大于 0', 400)
    }
    if (!materials.some((item) => item.code === body.materialCode)) {
      return fail(config, 42200, `产品 ${body.materialCode} 不存在`, 422)
    }
    Object.assign(order, {
      materialCode: body.materialCode,
      qty: body.qty,
      dueDate: body.dueDate,
      priority: body.priority ?? order.priority,
      customerId: body.customerId ?? null,
      routingId: body.routingId ?? null,
      remark: body.remark ?? null,
      extAttrs: body.extAttrs ?? order.extAttrs,
      version: order.version + 1,
      updatedAt: now()
    })
    return ok(config, order)
  }

  if (method === 'GET' && path === '/strategies') {
    return ok(config, paginate(strategies, params))
  }

  if (method === 'POST' && path === '/strategies') {
    const body = readBody<Partial<StrategyCreateRequest>>(config)
    if (!body.name) {
      return fail(config, 40000, '策略名称不能为空', 400)
    }
    if (!body.effectiveFrom) {
      return fail(config, 40000, '生效日期不能为空', 400)
    }
    if (body.effectiveTo && body.effectiveFrom && body.effectiveTo < body.effectiveFrom) {
      return fail(config, 42200, '失效日期不能早于生效日期', 422)
    }
    const rules = Array.isArray(body.rules) ? body.rules : []
    if (Array.isArray(body.rules)) {
      const ruleError = validateStrategyRules(body.rules)
      if (ruleError) {
        return fail(config, 42200, ruleError, 422)
      }
    }
    const strategy: Strategy = {
      id: uuid(),
      name: body.name,
      direction: body.direction ?? 'FORWARD',
      strategyVersion: body.strategyVersion ?? 1,
      effectiveFrom: body.effectiveFrom,
      effectiveTo: body.effectiveTo ?? null,
      initialRules: body.initialRules ?? {},
      optimizationRules: body.optimizationRules ?? {},
      applyCondition: body.applyCondition ?? {},
      rules,
      version: 1
    }
    strategies.unshift(strategy)
    return ok(config, strategy)
  }

  const cloneMatch = path.match(/^\/strategies\/([^/]+)\/clone$/)
  if (method === 'POST' && cloneMatch) {
    const strategy = strategies.find((item) => item.id === decodeURIComponent(cloneMatch[1]))
    if (!strategy) {
      return fail(config, 40400, '策略不存在', 404)
    }
    const cloned: Strategy = {
      ...strategy,
      id: uuid(),
      name: `${strategy.name}（副本）`,
      strategyVersion: strategy.strategyVersion + 1,
      initialRules: { ...(strategy.initialRules ?? {}) },
      optimizationRules: { ...(strategy.optimizationRules ?? {}) },
      applyCondition: { ...(strategy.applyCondition ?? {}) },
      rules: (strategy.rules ?? []).map((rule) => ({ ...rule, id: undefined })),
      version: 1
    }
    strategies.unshift(cloned)
    return ok(config, cloned)
  }

  const strategyDetailMatch = path.match(/^\/strategies\/([^/]+)$/)
  if (strategyDetailMatch) {
    const strategyId = decodeURIComponent(strategyDetailMatch[1])
    const strategy = strategies.find((item) => item.id === strategyId)
    if (!strategy) {
      return fail(config, 40400, '策略不存在', 404)
    }
    if (method === 'GET') {
      const detail: StrategyDetail = { ...strategy, rules: strategy.rules ?? [] }
      return ok(config, detail)
    }
    if (method === 'PUT') {
      const body = readBody<Partial<StrategyUpdateRequest>>(config)
      if (typeof body.version !== 'number') {
        return fail(config, 40000, 'version 为必填乐观锁字段', 400)
      }
      if (body.version !== strategy.version) {
        return fail(config, 40901, '策略已被修改，请刷新后重试', 409)
      }
      if (!body.name) {
        return fail(config, 40000, '策略名称不能为空', 400)
      }
      if (!body.effectiveFrom) {
        return fail(config, 40000, '生效日期不能为空', 400)
      }
      if (body.effectiveTo && body.effectiveTo < body.effectiveFrom) {
        return fail(config, 42200, '失效日期不能早于生效日期', 422)
      }
      if (!Array.isArray(body.rules)) {
        return fail(config, 40000, 'rules 必填，保存为整体替换', 400)
      }
      const ruleError = validateStrategyRules(body.rules)
      if (ruleError) {
        return fail(config, 42200, ruleError, 422)
      }
      strategy.name = body.name
      strategy.direction = body.direction ?? strategy.direction
      strategy.strategyVersion = body.strategyVersion ?? strategy.strategyVersion
      strategy.effectiveFrom = body.effectiveFrom
      strategy.effectiveTo = body.effectiveTo ?? null
      strategy.initialRules = body.initialRules ?? strategy.initialRules
      strategy.optimizationRules = body.optimizationRules ?? strategy.optimizationRules
      strategy.applyCondition = body.applyCondition ?? strategy.applyCondition
      strategy.rules = body.rules
      strategy.version += 1
      const detail: StrategyDetail = { ...strategy, rules: strategy.rules }
      return ok(config, detail)
    }
  }

  if (method === 'GET' && path === '/sandboxes') {
    const type = String(params.type ?? '').trim()
    // C32：列表默认不返回 ARCHIVED。
    const filtered = sandboxes.filter((item) => item.type !== 'ARCHIVED' && (!type || item.type === type))
    return ok(config, paginate(filtered, params))
  }

  if (method === 'POST' && path === '/sandboxes') {
    const body = readBody<Partial<SandboxCreateRequest>>(config)
    if (!body.name) {
      return fail(config, 40000, '场景名称不能为空', 400)
    }
    // C32：创建只允许 WHAT_IF，缺省即 WHAT_IF。
    if (body.type && body.type !== 'WHAT_IF') {
      return fail(config, 42200, '创建场景只允许 WHAT_IF 类型', 422)
    }
    const sandbox: Sandbox = {
      id: uuid(),
      name: body.name,
      type: 'WHAT_IF',
      parentSandboxId: body.parentSandboxId ?? DEFAULT_SANDBOX_ID,
      expiresAt: body.expiresAt ?? null,
      snapshot: { capturedAt: now(), note: 'Mock WHAT_IF 场景' },
      version: 1,
      createdAt: now()
    }
    sandboxes.unshift(sandbox)
    return ok(config, sandbox)
  }

  const applySandboxMatch = path.match(/^\/sandboxes\/([^/]+)\/apply$/)
  if (method === 'POST' && applySandboxMatch) {
    const sandbox = sandboxes.find((item) => item.id === decodeURIComponent(applySandboxMatch[1]))
    if (!sandbox) {
      return fail(config, 40400, '场景不存在', 404)
    }
    const idempotencyKey = config.headers?.get?.('Idempotency-Key')
    if (!idempotencyKey || typeof idempotencyKey !== 'string') {
      return fail(config, 40000, 'Idempotency-Key 请求头必填', 400)
    }
    if (sandbox.type !== 'WHAT_IF') {
      return fail(config, 42200, '只有 WHAT_IF 场景可以 apply 提升为 BASELINE', 422)
    }
    const reason = readBody<{ reason?: string }>(config).reason?.trim()
    if (!reason) {
      return fail(config, 40000, 'apply 必须填写 reason（写入审计）', 400)
    }
    // 原 BASELINE 归档，目标场景提升为 BASELINE 并发布新版本。
    sandboxes.forEach((item) => {
      if (item.type === 'BASELINE') {
        item.type = 'ARCHIVED'
      }
    })
    sandbox.type = 'BASELINE'
    sandbox.version += 1
    const planId = uuid()
    const plan = createMockPlan(planId)
    plan.action = {
      ...plan.action,
      sandboxId: sandbox.id,
      status: 'PUBLISHED',
      publishedAt: now(),
      taskCount: plan.tasks.length,
      affectedTaskCount: plan.tasks.length,
      approver: null,
      reason
    }
    mockPlanRecords.set(planId, plan)
    const result: SandboxApplyResult = { sandbox, plan: plan.action }
    return ok(config, result)
  }

  const compareSandboxMatch = path.match(/^\/sandboxes\/([^/]+)\/compare$/)
  if (method === 'GET' && compareSandboxMatch) {
    const sandboxId = decodeURIComponent(compareSandboxMatch[1])
    const targetId = String(params.targetId ?? '')
    const sandbox = sandboxes.find((item) => item.id === sandboxId)
    if (!sandbox) {
      return fail(config, 40400, '场景不存在', 404)
    }
    if (!targetId || targetId === sandboxId) {
      return fail(config, 42200, 'targetId 必须指定另一个场景', 422)
    }
    if (!sandboxes.some((item) => item.id === targetId)) {
      return fail(config, 40400, '对比目标场景不存在', 404)
    }
    // Mock 固定返回被截断的明细（counts 为全量），用于验证前端显式提示“结果被截断”。
    const compare: SandboxCompare = {
      sandboxId,
      targetId,
      dueDateDeltaMinutes: -180,
      utilizationDelta: 0.037,
      setupCountDelta: -2,
      addedTaskCount: 210,
      removedTaskCount: 90,
      movedTaskCount: 30,
      changedTasks: [
        { taskId: 2101, orderNo: 'DM-SO-1001', resourceCode: 'RES-SMT-01', changeType: 'MOVED' },
        { taskId: 2102, orderNo: 'DM-SO-1001', resourceCode: 'RES-PACK-01', changeType: 'MOVED' },
        { taskId: 2103, orderNo: 'DM-SO-1003', resourceCode: 'RES-AGING-01', changeType: 'ADDED' },
        { taskId: 2104, orderNo: 'DM-SO-1004', resourceCode: 'RES-SMT-01', changeType: 'REMOVED' },
        { taskId: 2105, orderNo: 'DM-SO-1008', resourceCode: 'RES-PACK-01', changeType: 'ADDED' }
      ],
      truncated: true
    }
    return ok(config, compare)
  }

  if (method === 'POST' && path === '/solve-tasks') {
    const body = readBody<Partial<SolveSubmitRequest>>(config)
    const taskNo = body.taskNo || `SOLVE-${Date.now()}`
    const detail: SolveTaskDetail = {
      id: nextNumericId(),
      taskNo,
      sandboxId: body.sandboxId ?? DEFAULT_SANDBOX_ID,
      strategyId: body.strategyId ?? null,
      engine: body.engine ?? 'timefold',
      status: 'PENDING',
      scoreHard: null,
      scoreSoft: null,
      progress: 0,
      phase: 'ASSEMBLING',
      bestScore: null,
      elapsedMs: 0,
      resultTaskId: null,
      startedAt: now(),
      finishedAt: null,
      metrics: { stepCount: 0, durationMs: 0 },
      errorMessage: null,
      createdAt: now()
    }
    solveTaskDetails.unshift(detail)
    // C20：异步求解返回 SolveTaskDetail，进度经 STOMP/轮询查询
    return ok(config, detail)
  }

  const solveTaskMatch = path.match(/^\/solve-tasks\/([^/]+)$/)
  if (method === 'GET' && solveTaskMatch) {
    const id = Number(solveTaskMatch[1])
    const detail = solveTaskDetails.find((item) => item.id === id)
    if (!detail) {
      return fail(config, 40400, '求解任务不存在', 404)
    }
    return ok(config, advanceMockSolveTask(detail))
  }

  const cancelSolveMatch = path.match(/^\/solve-tasks\/([^/]+)\/cancel$/)
  if (method === 'POST' && cancelSolveMatch) {
    const id = Number(cancelSolveMatch[1])
    const detail = solveTaskDetails.find((item) => item.id === id)
    if (!detail) {
      return fail(config, 40400, '求解任务不存在', 404)
    }
    if (['SOLVED', 'FAILED', 'CANCELLED', 'TIMEOUT'].includes(detail.status)) {
      return fail(config, 40901, '终态求解任务不可取消', 409)
    }
    detail.status = 'CANCELLED'
    return ok(config, detail)
  }

  if (path.startsWith('/agent/')) {
    return fail(config, 50100, '接口尚未实现（Mock 占位）', 501)
  }

  if (method === 'GET' && path === '/gantt/resource') {
    const codes = listParam(params.resourceCodes)
    const rows = codes.length
      ? ganttResourceRows.filter((row) => codes.includes(row.resourceCode))
      : ganttResourceRows
    return ok(config, {
      sandboxId: params.sandboxId || DEFAULT_SANDBOX_ID,
      from: params.from ?? '',
      to: params.to ?? '',
      resources: rows
    })
  }

  if (method === 'GET' && path === '/gantt/order') {
    const orderNos = listParam(params.orderNos)
    const rows = orderNos.length
      ? ganttOrderRows.filter((row) => orderNos.includes(row.orderNo))
      : ganttOrderRows
    return ok(config, {
      sandboxId: params.sandboxId || DEFAULT_SANDBOX_ID,
      from: params.from ?? '',
      to: params.to ?? '',
      orders: rows
    })
  }

  const adjustTaskMatch = path.match(/^\/tasks\/([^/]+)\/adjust$/)
  if (method === 'POST' && adjustTaskMatch) {
    const task = ganttTasks.find((item) => item.id === Number(adjustTaskMatch[1]))
    if (!task) {
      return fail(config, 40400, '排产任务不存在', 404)
    }
    const body = readBody<Partial<TaskAdjustRequest>>(config)
    if (body.version !== task.version) {
      return fail(config, 40901, '资源状态冲突，请刷新后重试', 409)
    }
    if (body.resourceCode !== undefined) {
      task.resourceCode = body.resourceCode
    }
    if (body.startTime) {
      task.startTime = body.startTime
    }
    if (body.endTime) {
      task.endTime = body.endTime
    }
    if (body.frozen !== undefined) {
      task.status = body.frozen ? 'FROZEN' : 'PLANNED'
    }
    task.version += 1
    return ok(config, task)
  }

  // C35 POST /plans/compare：先去重再判 2–3 个，响应已排序并附推荐与 tieBreak。
  if (method === 'POST' && path === '/plans/compare') {
    const body = readBody<{ planIds?: string[] }>(config)
    const unique = [...new Set((body.planIds ?? []).filter((planId) => Boolean(planId)))]
    if (unique.length < 2 || unique.length > 3) {
      return fail(config, 42200, 'planIds 去重后必须为 2–3 个', 422)
    }
    const items: PlanCompareItem[] = unique.map((planId) => {
      const record = mockPlanOf(planId)
      const hash = [...planId].reduce((sum, character) => sum + character.charCodeAt(0), 0)
      return {
        planId,
        planNo: record.action.planNo ?? '',
        version: record.action.version,
        scoreHard: hash % 7 === 0 ? -1 : 0,
        scoreSoft: -(hash % 60),
        taskCount: record.tasks.length,
        metrics: {
          durationMs: 15000 + (hash % 5000),
          engineFallbackReason: null,
          scoreHistory: [0, -120, -(hash % 60)],
          unassignedTaskCount: hash % 3
        }
      }
    })
    items.sort((left, right) => {
      const hardDiff = (right.scoreHard ?? Number.NEGATIVE_INFINITY) - (left.scoreHard ?? Number.NEGATIVE_INFINITY)
      if (hardDiff !== 0) {
        return hardDiff
      }
      const softDiff = (right.scoreSoft ?? Number.NEGATIVE_INFINITY) - (left.scoreSoft ?? Number.NEGATIVE_INFINITY)
      if (softDiff !== 0) {
        return softDiff
      }
      return left.planId.localeCompare(right.planId)
    })
    const result: PlanCompareResult = {
      items,
      recommendedPlanId: items[0]?.planId ?? null,
      tieBreak: 'SCORE_HARD_DESC,SCORE_SOFT_DESC,PLAN_ID_ASC'
    }
    return ok(config, result)
  }

  if (method === 'GET' && path === '/plans') {
    const sandboxId = String(config.params?.sandboxId ?? '')
    if (!sandboxId) {
      return fail(config, 40000, 'sandboxId不能为空', 400)
    }
    ensureSeedPlans(sandboxId)
    const page = Number(config.params?.page ?? 1)
    const size = Number(config.params?.size ?? 20)
    const all = [...mockPlanRecords.values()]
      .filter((record) => record.action.sandboxId === sandboxId)
      .map((record) => ({
        planId: record.action.planId,
        planNo: record.action.planNo ?? '',
        version: record.action.version,
        status: record.action.status,
        publishedAt: record.action.publishedAt ?? null,
        publishedBy: null,
        approver: record.action.approver ?? null,
        taskCount: record.action.taskCount ?? 0,
        rollbackOf: record.action.rollbackOf ?? null
      }))
      .sort((left, right) => {
        if (!left.publishedAt && !right.publishedAt) {
          return right.planId.localeCompare(left.planId)
        }
        if (!left.publishedAt) {
          return -1
        }
        if (!right.publishedAt) {
          return 1
        }
        return right.publishedAt.localeCompare(left.publishedAt)
      })
    const total = all.length
    const start = (page - 1) * size
    return ok(config, {
      items: all.slice(start, start + size),
      page,
      size,
      total,
      totalPages: size > 0 ? Math.ceil(total / size) : 0
    })
  }

  const planDetailMatch = path.match(/^\/plans\/([^/]+)$/)
  if (method === 'GET' && planDetailMatch) {
    return ok(config, mockPlanOf(decodeURIComponent(planDetailMatch[1])))
  }

  const planActionMatch = path.match(/^\/plans\/([^/]+)\/(publish|rollback)$/)
  if (method === 'POST' && planActionMatch) {
    const planId = decodeURIComponent(planActionMatch[1])
    const record = mockPlanOf(planId)
    const action = record.action
    if (planActionMatch[2] === 'publish') {
      const body = readBody<PlanPublishRequest>(config)
      const idempotent = action.status === 'PUBLISHED'
      if (!idempotent) {
        action.status = 'PUBLISHED'
        action.publishedAt = now()
        action.taskCount = record.tasks.length
        action.affectedTaskCount = record.tasks.length
        action.reason = body.reason?.trim() || action.reason
        action.approver = body.approver?.trim() || null
      } else {
        action.affectedTaskCount = 0
      }
      return ok(config, { ...action, targetVersionNo: null })
    }
    const body = readBody<PlanRollbackRequest>(config)
    if (!body.targetVersionNo || !Number.isInteger(body.targetVersionNo)) {
      return fail(config, 40000, 'targetVersionNo 必填且为 ≥1 的整数', 400)
    }
    if (body.targetVersionNo >= action.version) {
      return fail(config, 40000, '目标版本不存在或未发布', 400)
    }
    const target = [...mockPlanRecords.values()].find(
      (item) =>
        item.action.sandboxId === action.sandboxId && item.action.version === body.targetVersionNo
    )
    const created: PlanAction = {
      planId: uuid(),
      planNo: `PLAN-20261104-${String(action.version + 1).padStart(3, '0')}`,
      version: action.version + 1,
      targetVersionNo: body.targetVersionNo,
      status: 'PUBLISHED',
      publishedAt: now(),
      affectedTaskCount: record.tasks.length,
      sandboxId: action.sandboxId,
      taskCount: target?.action.taskCount ?? record.tasks.length,
      approver: null,
      reason: body.reason?.trim() || null,
      rollbackOf: planId
    }
    mockPlanRecords.set(created.planId, {
      action: created,
      tasks: target ? [...target.tasks] : [...record.tasks]
    })
    return ok(config, created)
  }

  // C38/C39 导入导出：/{entity}/import/template、/{entity}/import、/{entity}/export、异步任务查询与取消。
  const importTemplateMatch = path.match(/^\/([^/]+)\/import\/template$/)
  if (method === 'GET' && importTemplateMatch) {
    const entity = decodeURIComponent(importTemplateMatch[1])
    if (!MOCK_IMPORT_ENTITY_SLUGS.has(entity)) {
      return fail(config, 40400, `未知实体: ${entity}`, 404)
    }
    return binaryReply(`${entity}-import-template.xlsx`, `Mock 导入模板：${entity}`)
  }

  const importExportMatch = path.match(/^\/([^/]+)\/export$/)
  if (method === 'GET' && importExportMatch) {
    const entity = decodeURIComponent(importExportMatch[1])
    if (!MOCK_IMPORT_ENTITY_SLUGS.has(entity)) {
      return fail(config, 40400, `未知实体: ${entity}`, 404)
    }
    return binaryReply(`${entity}-export.xlsx`, `Mock 导出数据：${entity}`)
  }

  const importSubmitMatch = path.match(/^\/([^/]+)\/import$/)
  if (method === 'POST' && importSubmitMatch) {
    const entity = decodeURIComponent(importSubmitMatch[1])
    if (!MOCK_IMPORT_ENTITY_SLUGS.has(entity)) {
      return fail(config, 40400, `未知实体: ${entity}`, 404)
    }
    const mode = String(params.mode ?? 'preview').toLowerCase()
    if (mode !== 'preview' && mode !== 'execute' && mode !== 'async') {
      return fail(config, 40000, 'mode 只支持 preview/execute/async', 400)
    }
    const file = readUploadedFile(config)
    if (mode === 'async') {
      if (!file || file.size === 0) {
        return fail(config, 42200, '导入文件不能为空', 422)
      }
      if (file.size > IMPORT_MAX_FILE_BYTES) {
        return fail(config, 42200, '导入文件不能超过 5MB', 422)
      }
      return rawReply(createMockAsyncJob(entity, mockImportFails(file.name)), 202)
    }
    if (!file || file.size === 0) {
      return fail(config, 40000, '导入文件不能为空', 400)
    }
    if (file.size > IMPORT_MAX_FILE_BYTES) {
      return fail(config, 42200, '导入文件不能超过 5MB', 422)
    }
    return ok(
      config,
      mockSyncResult(entity, mockImportFails(file.name), mode === 'execute' ? 'EXECUTE' : 'PREVIEW')
    )
  }

  const importCancelMatch = path.match(/^\/([^/]+)\/import\/jobs\/([^/]+)\/cancel$/)
  if (method === 'POST' && importCancelMatch) {
    const entity = decodeURIComponent(importCancelMatch[1])
    const jobId = decodeURIComponent(importCancelMatch[2])
    const record = mockImportJobs.get(jobId)
    if (!record || record.view.entity !== entity) {
      return fail(config, 40400, '导入任务不存在', 404)
    }
    if (isTerminalImportStatus(record.view.status)) {
      return fail(config, 40901, `导入任务已结束（${record.view.status}），无法取消`, 409)
    }
    record.view = { ...record.view, status: 'CANCELLED', finishedAt: now() }
    return rawReply(record.view, 202)
  }

  const importJobMatch = path.match(/^\/([^/]+)\/import\/jobs\/([^/]+)$/)
  if (method === 'GET' && importJobMatch) {
    const entity = decodeURIComponent(importJobMatch[1])
    const jobId = decodeURIComponent(importJobMatch[2])
    const record = mockImportJobs.get(jobId)
    if (!record || record.view.entity !== entity) {
      return fail(config, 40400, '导入任务不存在', 404)
    }
    return rawReply(advanceMockImportJob(jobId), 200)
  }

  return fail(config, 40400, `Mock 未匹配到接口：${method} ${path}`, 404)
}
