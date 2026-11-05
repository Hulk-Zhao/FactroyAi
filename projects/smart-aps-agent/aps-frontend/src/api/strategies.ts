import axios from 'axios'

import http, { ApiError, createTraceId, idempotencyHeaders } from './http'
import type { Result } from '@/types/common'
import type {
  StrategyExportPackage,
  StrategyImportRequest,
  StrategyImportResult
} from '@/types/strategies'

/**
 * 导入专用静默客户端：校验失败（尤其 42200 checksum）需要页面给出可读文案，
 * 不能复用全局拦截器的 ElMessage，否则会把后端 message 原样弹出。
 * baseURL 复用共享实例，避免环境变量分叉。
 */
const importClient = axios.create({
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

/** 导出策略：C19 `GET /strategies/{id}/export` → `{strategy, rules, checksum}`。 */
export async function exportStrategy(strategyId: string): Promise<StrategyExportPackage> {
  const response = await http.get<StrategyExportPackage>(
    `/strategies/${encodeURIComponent(strategyId)}/export`
  )
  return response.data
}

/**
 * 导入策略：C19 `POST /strategies/import`，请求 `{payload, dryRun}`。
 * 试运行（dryRun=true）与真导共用后端同一校验器；本函数不自动弹提示，错误交给调用方处理。
 */
export async function importStrategy(
  request: StrategyImportRequest,
  idempotencyKey?: string
): Promise<StrategyImportResult> {
  try {
    const response = await importClient.post<Result<StrategyImportResult>>(
      '/strategies/import',
      request,
      {
        // 复用共享实例的适配器：Mock 开启时导入也走同一套 Mock 适配器。
        adapter: http.defaults.adapter,
        headers: {
          'X-Trace-Id': createTraceId(),
          ...idempotencyHeaders(idempotencyKey)
        }
      }
    )
    const envelope = response.data
    if (envelope.code !== 0) {
      throw new ApiError(envelope.code, envelope.message || '导入失败', envelope.traceId ?? '')
    }
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
        status ? `导入请求失败（HTTP ${status}）` : '网络连接失败，请检查后端服务或代理配置',
        '',
        status
      )
    }
    throw new ApiError(0, '导入请求异常，请稍后重试')
  }
}
