/**
 * system 域类型，字段对齐 docs/api/openapi/system.yaml；
 * users.status 取值对齐 docs/db/schema.md 第 4.1 节 CHECK 取值。
 */

export interface Health {
  status: string
  service: string
}

export interface LoginRequest {
  username: string
  password: string
}

/** C30 `POST /auth/login`：JWT HS256，TTL 默认 28800s，M3 无 refresh token。 */
export interface LoginResult {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
  userId: string
}

export type UserStatus = 'ACTIVE' | 'DISABLED' | 'LOCKED'

/** C30 权限码闭集（V1 的 18 个 permission.code，不得新增/改名）。 */
export type PermissionCode =
  | 'MD_READ'
  | 'MD_WRITE'
  | 'PR_READ'
  | 'PR_WRITE'
  | 'OM_READ'
  | 'OM_WRITE'
  | 'OM_ATP'
  | 'ORDER_FREEZE'
  | 'SS_READ'
  | 'SS_WRITE'
  | 'SE_SOLVE'
  | 'SCHEDULE_ADJUST'
  | 'SCHEDULE_PUBLISH'
  | 'SYS_USER'
  | 'SYS_ROLE'
  | 'SYS_AUDIT'
  | 'AGENT_EVENT'
  | 'AGENT_CONFIRM'

/** C30 角色码闭集（V1 种子，不可删除、不可改码）。 */
export type RoleCode = 'ADMIN' | 'PLANNER' | 'PROCESS_ENGINEER' | 'SALES' | 'VIEWER'

export interface User {
  id: string
  username: string
  displayName?: string | null
  email?: string | null
  phone?: string | null
  status: UserStatus
  lastLoginAt?: string | null
  version: number
  /** C30 新增：用户角色码，后端响应必带。 */
  roleCodes: string[]
}

export interface UserCreateRequest {
  username: string
  displayName?: string
  email?: string
  phone?: string
  /** C30：M3 中 password 必填，bcrypt 落库。 */
  password: string
  roleCodes?: string[]
}

/** C30 `PATCH /users/{userId}`：roleCodes 为全量替换，version 必填作为乐观锁。 */
export interface UserUpdateRequest {
  displayName?: string | null
  email?: string | null
  phone?: string | null
  status?: UserStatus
  roleCodes?: string[]
  password?: string
  version: number
}

/** C30 `GET /permissions` 返回裸数组，元素为权限点。 */
export interface PermissionView {
  id: string
  code: PermissionCode
  name: string
  module: string
  description?: string | null
}

/** C30 `GET /me`：User + 当前用户权限并集，供页面做入口控制。 */
export interface MeView extends User {
  permissions: PermissionCode[]
}

/** C30 `GET /roles` 返回 `{items}`（不是分页外壳）。 */
export interface RoleView {
  id: string
  code: RoleCode | string
  name: string
  description?: string | null
  permissionCodes: PermissionCode[]
  version: number
}

export interface RoleList {
  items: RoleView[]
}

export interface RoleCreateRequest {
  code: string
  name: string
  description?: string
  permissionCodes?: PermissionCode[]
}

export interface RolePermissionsRequest {
  permissionCodes: PermissionCode[]
}

/** C31 审计事件类型闭集（16 个 action）。 */
export type AuditAction =
  | 'ORDER_FROZEN'
  | 'ORDER_PRIORITY_CHANGED'
  | 'ORDER_RELEASED'
  | 'AUTH_LOGIN'
  | 'AUTH_LOGIN_FAILED'
  | 'USER_CREATED'
  | 'USER_UPDATED'
  | 'ROLE_CREATED'
  | 'ROLE_PERMISSION_CHANGED'
  | 'PLAN_PUBLISHED'
  | 'PLAN_ROLLBACK'
  | 'PLAN_DISPATCHED'
  | 'SANDBOX_CREATED'
  | 'SANDBOX_APPLIED'
  | 'IMPORT_EXECUTED'
  | 'TASK_ADJUSTED'

/** C31 资源类型闭集。 */
export type AuditResourceType = 'USER' | 'ROLE' | 'ORDER' | 'PLAN' | 'SANDBOX' | 'TASK' | 'IMPORT'

export interface AuditLog {
  /**
   * audit_log.id 为 int64（BIGSERIAL）。这里刻意使用 string：
   * JS number 无法安全承载超过 2^53-1 的整数，API 层会把超大数字转成字符串，
   * 页面也只做展示，不参与数值运算。
   */
  id: string
  traceId: string
  userId?: string | null
  action: AuditAction | string
  resourceType: AuditResourceType | string
  resourceId: string
  beforeValue?: Record<string, unknown> | null
  afterValue?: Record<string, unknown> | null
  clientIp?: string | null
  createdAt: string
}

/** C31 `GET /audit-logs` 查询参数；时间区间为 `[from, to)`。 */
export interface AuditLogQuery {
  page?: number
  size?: number
  action?: string
  resourceType?: string
  resourceId?: string
  userId?: string
  from?: string
  to?: string
  sort?: string
}

export interface SystemConfig {
  key: string
  value: string
  description?: string | null
  updatedAt: string
}

export interface ConfigUpdateRequest {
  items: Array<{ key: string; value: string }>
}
