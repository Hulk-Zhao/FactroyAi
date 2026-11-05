import { AxiosError, AxiosHeaders } from 'axios'
import type { AxiosAdapter, AxiosResponse } from 'axios'

import { handleMockRequest } from './handlers'

const configuredDelay = Number(import.meta.env.VITE_MOCK_DELAY)
const delayMs = Number.isFinite(configuredDelay) ? Math.max(configuredDelay, 0) : 300

function statusText(status: number): string {
  if (status < 400) {
    return 'OK'
  }
  return status === 501 ? 'Not Implemented' : 'Mock Error'
}

function traceIdOf(body: unknown): string {
  if (typeof body !== 'object' || body === null) {
    return ''
  }
  const value = (body as { traceId?: unknown }).traceId
  return typeof value === 'string' ? value : ''
}

function messageOf(body: unknown): string {
  if (typeof body === 'object' && body !== null) {
    const value = (body as { message?: unknown }).message
    if (typeof value === 'string') {
      return value
    }
  }
  return 'Mock Error'
}

/** Axios 自定义适配器：命中契约 Mock 时返回 Result 信封或二进制 Blob，未命中返回 404。 */
export const mockAdapter: AxiosAdapter = async (config) => {
  if (delayMs > 0) {
    await new Promise((resolve) => setTimeout(resolve, delayMs))
  }
  const reply = handleMockRequest(config)
  const headers = new AxiosHeaders(reply.headers)
  const traceId = traceIdOf(reply.body)
  if (traceId && !headers.has('x-trace-id')) {
    headers.set('x-trace-id', traceId)
  }
  const response: AxiosResponse = {
    data: reply.body,
    status: reply.status,
    statusText: statusText(reply.status),
    headers,
    config,
    request: {}
  }
  if (reply.status >= 400) {
    throw new AxiosError(messageOf(reply.body), String(reply.status), config, {}, response)
  }
  return response
}
