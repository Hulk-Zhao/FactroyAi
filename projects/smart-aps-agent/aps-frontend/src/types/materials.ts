/**
 * materials 域类型，字段对齐 docs/api/openapi/materials.yaml；
 * 枚举取值对齐 docs/db/schema.md 第 4.1 节 CHECK 取值。
 * BOM 类型已拆分至 types/boms.ts，此处保留 re-export 以兼容既有引用。
 */
export type { Bom, BomCreateRequest, BomItem, BomStatus, BomTreeNode, BomQuery } from './boms'

export type MaterialType = 'FINISHED' | 'SEMI' | 'RAW' | 'AUX' | 'PACK'

export interface Material {
  code: string
  name: string
  type: MaterialType
  spec?: string
  unit: string
  categoryId?: string | null
  safetyStock?: number
  moq?: number
  leadTimeDays?: number
  defaultSupplierId?: string | null
  extAttrs?: Record<string, unknown>
  industryAttrs?: Record<string, unknown>
  version: number
  createdAt?: string
  updatedAt?: string
}

export interface MaterialCreateRequest {
  code: string
  name: string
  type: MaterialType
  spec?: string
  unit: string
  categoryId?: string | null
  safetyStock?: number
  moq?: number
  leadTimeDays?: number
  defaultSupplierId?: string | null
  extAttrs?: Record<string, unknown>
  industryAttrs?: Record<string, unknown>
}

export interface MaterialUpdateRequest extends MaterialCreateRequest {
  version: number
}

export interface MaterialQuery {
  type?: MaterialType
  /** 编码模糊筛选；materials.yaml 未列此参数，属前端兼容性扩展。 */
  code?: string
  /** 名称模糊筛选；materials.yaml 未列此参数，属前端兼容性扩展。 */
  name?: string
}

export interface ImportError {
  row: number
  code: string
  message: string
}

export interface ImportResult {
  successCount: number
  failureCount: number
  errors: ImportError[]
}

export type ResourceType = 'PRIMARY' | 'SECONDARY' | 'HUMAN' | 'TOOL'

export type CapacityMode = 'UNIT_PER_HOUR' | 'SECONDS_PER_UNIT' | 'BATCH_SECONDS'

export interface Resource {
  /** 资源 UUID，C14 起资源接口返回 id。 */
  id: string
  code: string
  name: string
  type: ResourceType
  groupId?: string | null
  capacityMode: CapacityMode
  capacityValue: number
  batchCapacity?: number
  efficiency?: number
  calendarId?: string | null
  setupSeconds?: number
  teardownSeconds?: number
  maintenancePlan?: Record<string, unknown>
  industryAttrs?: Record<string, unknown>
  version: number
}

export interface ResourceCreateRequest {
  code: string
  name: string
  type: ResourceType
  groupId?: string | null
  capacityMode: CapacityMode
  capacityValue: number
  batchCapacity?: number
  efficiency?: number
  calendarId?: string | null
  setupSeconds?: number
  teardownSeconds?: number
  industryAttrs?: Record<string, unknown>
}

export interface MaintenanceWindow {
  startTime: string
  endTime: string
  reason?: string
}

export interface MaintenanceRequest {
  effectiveFrom: string
  effectiveTo: string
  windows: MaintenanceWindow[]
}

export type CalendarType = 'FACTORY' | 'RESOURCE' | 'SHIFT'

export interface Shift {
  startTime: string
  endTime: string
  daysOfWeek: number[]
}

export interface Holiday {
  date: string
  name: string
  kind: 'LEGAL' | 'COMPANY' | 'MAKEUP'
}

export interface Calendar {
  id: string
  name: string
  calendarVersion: number
  type: CalendarType
  resourceId?: string | null
  resourceGroupId?: string | null
  effectiveFrom: string
  effectiveTo?: string | null
  shifts: Shift[]
  holidays?: Holiday[]
  version: number
}

export interface CalendarCreateRequest {
  name: string
  calendarVersion: number
  type: CalendarType
  resourceId?: string | null
  resourceGroupId?: string | null
  effectiveFrom: string
  effectiveTo?: string | null
  shifts: Shift[]
  holidays?: Holiday[]
}

export interface AvailableTimeSlice {
  startTime: string
  endTime: string
  minutes: number
}

export interface AvailableTime {
  items: AvailableTimeSlice[]
}

export interface RoutingNode {
  operationCode: string
  operationName: string
  resourceCode: string
  sequence?: number
  standardSeconds?: number
  setupSeconds?: number
  industryAttrs?: Record<string, unknown>
}

export interface RoutingEdge {
  from: string
  to: string
  minLagMinutes?: number
}

export interface Routing {
  id: string
  productCode: string
  routingVersion: string
  effectiveDate: string
  expiryDate?: string | null
  nodes: RoutingNode[]
  edges: RoutingEdge[]
  version: number
}

export interface RoutingCreateRequest {
  productCode: string
  routingVersion: string
  effectiveDate: string
  expiryDate?: string | null
  nodes: RoutingNode[]
  edges: RoutingEdge[]
}

export interface RoutingGraph {
  routingId: string
  nodes: RoutingNode[]
  edges: RoutingEdge[]
}
