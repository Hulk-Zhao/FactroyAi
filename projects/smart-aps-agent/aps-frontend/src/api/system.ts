import axios from 'axios'

import http, {
  ApiError,
  createTraceId,
  idempotencyHeaders,
  setAuthToken,
  withPageQuery
} from './http'
import type { PageQuery, PageResult, Result } from '@/types/common'
import type {
  AuditLog,
  AuditLogQuery,
  ConfigUpdateRequest,
  Health,
  LoginRequest,
  LoginResult,
  MeView,
  PermissionCode,
  PermissionView,
  RoleCreateRequest,
  RoleList,
  RoleView,
  SystemConfig,
  User,
  UserCreateRequest,
  UserUpdateRequest
} from '@/types/system'

/**
 * 登录专用静默客户端：登录失败（40100/40300/423）需要在页面内联展示并做文案映射，
 * 不能复用全局拦截器的 ElMessage（否则会再弹一次后端原文）。
 * baseURL 与适配器复用共享实例，避免 Mock 开启时登录请求绕过 Mock。
 */
const loginClient = axios.create({
  baseURL: http.defaults.baseURL,
  timeout: 30_000,
  headers: { Accept: 'application/json' }
})

function readEnvelope(value: unknown): Result<unknown> | null {
  if (typeof value !== 'object' || value === null) {
    return null
  }
  const record = value as Record<string, unknown>
  return typeof record.code === 'number' && typeof record.message === 'string'
    ? (value as Result<unknown>)
    : null
}

export async function fetchHealth(): Promise<Health> {
  const response = await http.get<Health>('/health')
  return response.data
}

/**
 * C30 `POST /auth/login`：JWT（HS256，TTL 28800s，无 refresh token）。
 * 成功后统一写入 localStorage，Authorization 注入由 http 请求拦截器负责。
 */
export async function login(payload: LoginRequest): Promise<LoginResult> {
  try {
    const response = await loginClient.post<Result<LoginResult>>('/auth/login', payload, {
      adapter: http.defaults.adapter,
      headers: { 'X-Trace-Id': createTraceId() }
    })
    const envelope = response.data
    if (envelope.code !== 0) {
      throw new ApiError(envelope.code, envelope.message || '登录失败', envelope.traceId ?? '')
    }
    setAuthToken(envelope.data.accessToken)
    return envelope.data
  } catch (error) {
    if (error instanceof ApiError) {
      throw error
    }
    if (axios.isAxiosError(error)) {
      const status = error.response?.status
      const envelope = readEnvelope(error.response?.data)
      if (envelope) {
        throw new ApiError(envelope.code, envelope.message, envelope.traceId || '', status)
      }
      throw new ApiError(
        0,
        status ? `登录请求失败（HTTP ${status}）` : '网络连接失败，请检查后端服务或代理配置',
        '',
        status
      )
    }
    throw new ApiError(0, '登录请求异常，请稍后重试')
  }
}

/** C30 `GET /me`：当前用户 + 权限并集。 */
export async function fetchMe(): Promise<MeView> {
  const response = await http.get<MeView>('/me')
  return response.data
}

export async function listUsers(
  query: PageQuery & { status?: string } = {}
): Promise<PageResult<User>> {
  const response = await http.get<PageResult<User>>('/users', {
    params: withPageQuery(query, { status: query.status })
  })
  return response.data
}

export async function createUser(
  payload: UserCreateRequest,
  idempotencyKey?: string
): Promise<User> {
  const response = await http.post<User>('/users', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

/** C30 `PATCH /users/{userId}`：version 为必填乐观锁，roleCodes 全量替换。 */
export async function updateUser(
  userId: string,
  payload: UserUpdateRequest,
  idempotencyKey?: string
): Promise<User> {
  const response = await http.patch<User>(`/users/${encodeURIComponent(userId)}`, payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

/**
 * C30 `GET /roles`：真实响应是 `{items: RoleView[]}`，不是 PageResult，也没有分页参数。
 * 前端按真实形状解析，不在这里“抹平”成裸数组。
 */
export async function listRoles(): Promise<RoleList> {
  const response = await http.get<RoleList>('/roles')
  return response.data
}

export async function createRole(
  payload: RoleCreateRequest,
  idempotencyKey?: string
): Promise<RoleView> {
  const response = await http.post<RoleView>('/roles', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

/** C30 `PUT /roles/{roleId}/permissions`：全量替换，空数组合法。 */
export async function replaceRolePermissions(
  roleId: string,
  permissionCodes: PermissionCode[],
  idempotencyKey?: string
): Promise<RoleView> {
  const response = await http.put<RoleView>(
    `/roles/${encodeURIComponent(roleId)}/permissions`,
    { permissionCodes },
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}

/**
 * C30 `GET /permissions`：真实响应是裸数组（与 `/roles` 的 `{items}` 不一致，这是既有事实），
 * 前端按裸数组解析并保留该差异，不在代码里伪装成统一形状。
 */
export async function listPermissions(): Promise<PermissionView[]> {
  const response = await http.get<PermissionView[]>('/permissions')
  return response.data
}

/**
 * C31 审计 int64 精度保护：Jackson 按 record 声明顺序输出 `{"id":123,"traceId":"..."}`，
 * 只把这一处 id 引号化，绝不触碰 beforeValue/afterValue 里的同名字段。
 * 小于 2^53 的 id 本来也安全，但统一按字符串处理可避免页面误用 number 运算。
 */
function preserveAuditInt64(raw: unknown): unknown {
  if (typeof raw !== 'string') {
    return raw
  }
  return JSON.parse(raw.replace(/"id":(\d+),"traceId"/g, '"id":"$1","traceId"'))
}

/** C31 `GET /audit-logs`：过滤条件 AND，时间区间 `[from, to)`。 */
export async function listAuditLogs(query: AuditLogQuery = {}): Promise<PageResult<AuditLog>> {
  const { page, size, sort, action, resourceType, resourceId, userId, from, to } = query
  const response = await http.get<PageResult<AuditLog>>('/audit-logs', {
    params: withPageQuery(
      { page, size, sort },
      { action, resourceType, resourceId, userId, from, to }
    ),
    transformResponse: [preserveAuditInt64]
  })
  return response.data
}

export async function getConfigs(): Promise<SystemConfig[]> {
  const response = await http.get<SystemConfig[]>('/configs')
  return response.data
}

export async function updateConfigs(
  payload: ConfigUpdateRequest,
  idempotencyKey?: string
): Promise<SystemConfig[]> {
  const response = await http.put<SystemConfig[]>('/configs', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}
