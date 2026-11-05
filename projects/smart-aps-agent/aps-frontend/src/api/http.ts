import axios, { AxiosHeaders, AxiosError } from 'axios'
import { ElMessage } from 'element-plus'

import { isServerErrorCode, resolveErrorMessage } from '@/constants/error-codes'
import { useTraceStore } from '@/stores/trace'
import type { PageQuery, QueryParams, Result } from '@/types/common'

/** 业务错误：code!=0 或网络/HTTP 异常统一抛此类。 */
export class ApiError extends Error {
  readonly code: number
  readonly traceId: string
  readonly httpStatus?: number

  constructor(code: number, message: string, traceId = '', httpStatus?: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
    this.httpStatus = httpStatus
  }
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  timeout: 30_000,
  headers: { Accept: 'application/json' },
  paramsSerializer: {
    serialize: serializeParams
  }
})

/** C30 访问令牌存储键；token 只落 localStorage，页面不得各自散存。 */
const AUTH_TOKEN_KEY = 'aps.accessToken'

export function getAuthToken(): string {
  try {
    return globalThis.localStorage?.getItem(AUTH_TOKEN_KEY) ?? ''
  } catch {
    return ''
  }
}

export function setAuthToken(token: string): void {
  try {
    globalThis.localStorage?.setItem(AUTH_TOKEN_KEY, token)
  } catch {
    // 隐私模式禁用 localStorage 时忽略，登录仍可继续使用当前会话。
  }
}

export function clearAuthToken(): void {
  try {
    globalThis.localStorage?.removeItem(AUTH_TOKEN_KEY)
  } catch {
    // 同上。
  }
}

/** 分页/查询参数序列化：数组按逗号拼接，跳过空值。 */
function serializeParams(params: Record<string, unknown>): string {
  const search = new URLSearchParams()
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') {
      return
    }
    if (Array.isArray(value)) {
      const joined = value
        .filter((item) => item !== undefined && item !== null && item !== '')
        .join(',')
      if (joined) {
        search.append(key, joined)
      }
      return
    }
    search.append(key, String(value))
  })
  return search.toString()
}

/** 生成合法 traceId：[A-Za-z0-9._-]{8,64}。 */
export function createTraceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '')
  }
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`
}

function isResultEnvelope(value: unknown): value is Result<unknown> {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const record = value as Record<string, unknown>
  return typeof record.code === 'number' && typeof record.message === 'string'
}

function readTraceIdFromHeaders(headers: unknown): string {
  if (!headers || typeof headers !== 'object') {
    return ''
  }
  const record = headers as Record<string, unknown>
  const value = record['x-trace-id'] ?? record['X-Trace-Id']
  return typeof value === 'string' ? value : ''
}

function setTrace(traceId: string) {
  if (!traceId) {
    return
  }
  try {
    useTraceStore().setTraceId(traceId)
  } catch {
    // Pinia 尚未初始化时忽略（例如单测直接调用 http）。
  }
}

/** 统一错误提示：5xxxx 只展示默认文案，trace 详情写入控制台。 */
function notifyError(error: ApiError) {
  if (isServerErrorCode(error.code)) {
    console.error('[api] server error', {
      code: error.code,
      message: error.message,
      traceId: error.traceId,
      httpStatus: error.httpStatus
    })
  } else {
    console.warn('[api] business error', {
      code: error.code,
      message: error.message,
      traceId: error.traceId
    })
  }
  const suffix = error.traceId ? `（traceId: ${error.traceId.slice(0, 8)}）` : ''
  ElMessage.error(`${error.message}${suffix}`)
}

http.interceptors.request.use((config) => {
  const headers = config.headers ?? new AxiosHeaders()
  if (!headers.has('X-Trace-Id')) {
    headers.set('X-Trace-Id', createTraceId())
  }
  // C30：登录成功后由 setAuthToken 统一存储，这里只负责注入，不在页面硬编码 token。
  const token = getAuthToken()
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  config.headers = headers
  return config
})

http.interceptors.response.use(
  (response) => {
    const envelope = response.data
    const traceId = readTraceIdFromHeaders(response.headers)
    if (isResultEnvelope(envelope)) {
      setTrace(traceId || envelope.traceId)
      if (envelope.code !== 0) {
        const error = new ApiError(
          envelope.code,
          resolveErrorMessage(envelope.code, envelope.message),
          envelope.traceId || traceId,
          response.status
        )
        notifyError(error)
        throw error
      }
      response.data = envelope.data
      return response
    }
    setTrace(traceId)
    return response
  },
  (error: unknown) => {
    if (error instanceof ApiError) {
      throw error
    }
    if (axios.isAxiosError(error)) {
      const axiosError = error as AxiosError<unknown>
      const traceId =
        readTraceIdFromHeaders(axiosError.response?.headers) ||
        (isResultEnvelope(axiosError.response?.data) ? axiosError.response?.data.traceId : '')
      setTrace(traceId)
      if (isResultEnvelope(axiosError.response?.data)) {
        const envelope = axiosError.response.data
        const apiError = new ApiError(
          envelope.code,
          resolveErrorMessage(envelope.code, envelope.message),
          envelope.traceId || traceId,
          axiosError.response?.status
        )
        notifyError(apiError)
        throw apiError
      }
      const status = axiosError.response?.status
      const code = status === 401 ? 40100 : status === 403 ? 40300 : status === 404 ? 40400 : 0
      const message = status
        ? resolveErrorMessage(code, `请求失败（HTTP ${status}）`)
        : '网络连接失败，请检查后端服务或代理配置'
      const apiError = new ApiError(code, message, traceId, status)
      notifyError(apiError)
      throw apiError
    }
    const apiError = new ApiError(0, '请求异常，请稍后重试')
    notifyError(apiError)
    throw apiError
  }
)

/** 分页参数整理：剔除空值、限制 size 上限 200。 */
export function normalizePageQuery(query: PageQuery = {}): PageQuery {
  const page = query.page && query.page > 0 ? query.page : 1
  const size = query.size ? Math.min(Math.max(query.size, 1), 200) : 20
  const sort = query.sort
  return { page, size, sort }
}

/** 合并分页与业务筛选参数。 */
export function withPageQuery(
  query: PageQuery = {},
  filters: QueryParams = {}
): Record<string, unknown> {
  return { ...normalizePageQuery(query), ...filters }
}

/** 写接口可按契约携带幂等键，格式 8-128 位 [A-Za-z0-9._:-]。 */
export function createIdempotencyKey(prefix = 'aps'): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export function idempotencyHeaders(idempotencyKey?: string): Record<string, string> {
  return idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}
}

export default http
