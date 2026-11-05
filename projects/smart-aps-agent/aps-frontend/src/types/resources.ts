/**
 * resources 域类型，字段对齐 docs/api/openapi/materials.yaml 的 Resource 段落；
 * 枚举取值对齐 docs/db/schema.md 4.1 节 CHECK 取值（resource.type / capacity_mode）。
 *
 * 契约缺口（详见交付汇报）：
 * - yaml 仅定义 GET/POST /resources 与 POST /resources/{code}/maintenance，
 *   未定义 GET/PUT/DELETE /resources/{code}；本文件的详情/更新/停用请求按 REST 惯例预留，
 *   需 S0 补 yaml 后后端才能对齐。
 * - resource 表无 status 列（只有 deleted），yaml Resource 也无 status；这里的 status
 *   仅作为前端展示字段，缺省按 ACTIVE 展示；契约 C14 明确列表不引入 status 筛选。
 */
export type ResourceType = 'PRIMARY' | 'SECONDARY' | 'HUMAN' | 'TOOL'

export type CapacityMode = 'UNIT_PER_HOUR' | 'SECONDS_PER_UNIT' | 'BATCH_SECONDS'

/** 契约缺口字段：缺省按 ACTIVE 处理。 */
export type ResourceStatus = 'ACTIVE' | 'INACTIVE'

export interface Resource {
  /** 资源 UUID（C14 起资源接口返回 id）。 */
  id: string
  code: string
  name: string
  type: ResourceType
  groupId?: string | null
  capacityMode: CapacityMode
  capacityValue: number
  batchCapacity?: number | null
  efficiency?: number
  calendarId?: string | null
  setupSeconds?: number
  teardownSeconds?: number
  maintenancePlan?: Record<string, unknown>
  industryAttrs?: Record<string, unknown>
  status?: ResourceStatus
  version: number
  createdAt?: string
  updatedAt?: string
}

export interface ResourceCreateRequest {
  code: string
  name: string
  type: ResourceType
  groupId?: string | null
  capacityMode: CapacityMode
  capacityValue: number
  batchCapacity?: number | null
  efficiency?: number
  calendarId?: string | null
  setupSeconds?: number
  teardownSeconds?: number
  industryAttrs?: Record<string, unknown>
}

export interface ResourceUpdateRequest extends ResourceCreateRequest {
  version: number
}

export interface ResourceQuery {
  /** 编码模糊筛选；yaml 未列，属前端兼容性扩展。 */
  code?: string
  /** 名称模糊筛选；yaml 未列，属前端兼容性扩展。 */
  name?: string
  type?: ResourceType
  /** 资源组 UUID；yaml 未列，属前端兼容性扩展。 */
  groupId?: string
}
