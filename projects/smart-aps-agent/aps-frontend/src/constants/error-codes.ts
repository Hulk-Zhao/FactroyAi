/**
 * 错误码字典，来源：docs/api/conventions.md 第 3 节。
 * 4xxxx 可直接展示服务端 message；5xxxx 只展示默认文案，细节仅进日志。
 */
export const ERROR_CODES = {
  SUCCESS: 0,
  BAD_REQUEST: 40000,
  UNAUTHORIZED: 40100,
  FORBIDDEN: 40300,
  NOT_FOUND: 40400,
  METHOD_NOT_ALLOWED: 40500,
  IDEMPOTENCY_CONFLICT: 40900,
  STATE_CONFLICT: 40901,
  /** 已发布的计划/任务不可回滚或覆盖（C24 回滚、C21 增量覆盖）。 */
  PUBLISHED_CONFLICT: 40903,
  UNSUPPORTED_MEDIA_TYPE: 41500,
  UNPROCESSABLE: 42200,
  TOO_MANY_REQUESTS: 42900,
  INTERNAL_ERROR: 50000,
  SOLVER_UNAVAILABLE: 50010,
  NOT_IMPLEMENTED: 50100,
  SERVICE_UNAVAILABLE: 50300
} as const

export type ErrorCode = (typeof ERROR_CODES)[keyof typeof ERROR_CODES]

export const ERROR_MESSAGES: Record<number, string> = {
  [ERROR_CODES.BAD_REQUEST]: '请求参数不合法',
  [ERROR_CODES.UNAUTHORIZED]: '未认证或凭证已失效',
  [ERROR_CODES.FORBIDDEN]: '无权访问该资源',
  [ERROR_CODES.NOT_FOUND]: '资源不存在',
  [ERROR_CODES.METHOD_NOT_ALLOWED]: '不支持的请求方法',
  [ERROR_CODES.IDEMPOTENCY_CONFLICT]: '幂等键冲突：相同请求正在处理中',
  [ERROR_CODES.STATE_CONFLICT]: '资源状态冲突，请刷新后重试',
  [ERROR_CODES.PUBLISHED_CONFLICT]: '已发布的计划/任务不可回滚或覆盖',
  [ERROR_CODES.UNSUPPORTED_MEDIA_TYPE]: '不支持的 Content-Type',
  [ERROR_CODES.UNPROCESSABLE]: '业务规则校验未通过',
  [ERROR_CODES.TOO_MANY_REQUESTS]: '请求过于频繁，请稍后重试',
  [ERROR_CODES.INTERNAL_ERROR]: '系统内部错误',
  [ERROR_CODES.SOLVER_UNAVAILABLE]: '求解引擎不可用',
  [ERROR_CODES.NOT_IMPLEMENTED]: '接口尚未实现',
  [ERROR_CODES.SERVICE_UNAVAILABLE]: '依赖服务暂不可用'
}

export function isClientErrorCode(code: number): boolean {
  return code >= 40000 && code < 50000
}

export function isServerErrorCode(code: number): boolean {
  return code >= 50000
}

/** 业务错误提示：4xxxx 优先服务端 message，5xxxx 使用默认文案。 */
export function resolveErrorMessage(code: number, message?: string): string {
  if (isClientErrorCode(code) && message) {
    return message
  }
  return ERROR_MESSAGES[code] ?? message ?? '请求失败'
}
